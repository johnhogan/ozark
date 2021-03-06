/*
 * Copyright © 2017 Ivar Grimstad (ivar.grimstad@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.mvcspec.ozark.binding.validate;

import org.mvcspec.ozark.binding.BindingResultImpl;
import org.mvcspec.ozark.binding.ConstraintViolationTranslator;
import org.mvcspec.ozark.binding.ValidationErrorImpl;
import org.mvcspec.ozark.cdi.OzarkInternal;

import javax.annotation.Priority;
import javax.inject.Inject;
import javax.interceptor.AroundInvoke;
import javax.interceptor.Interceptor;
import javax.interceptor.InvocationContext;
import javax.mvc.MvcContext;
import javax.mvc.binding.ValidationError;
import javax.validation.ConstraintViolation;
import javax.validation.Validator;
import javax.validation.ValidatorFactory;
import javax.validation.executable.ExecutableValidator;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * CDI backed interceptor to handle validation and binding issues.
 *
 * @author Dmytro Maidaniuk
 * @author Christian Kaltepoth
 */
@Interceptor
@ValidationInterceptorBinding
@Priority(Interceptor.Priority.LIBRARY_BEFORE)
public class ValidationInterceptor implements Serializable {

    private static final long serialVersionUID = -5804986456381504613L;

    private static final Logger log = Logger.getLogger(ValidationInterceptor.class.getName());

    @Inject
    @OzarkInternal
    private ValidatorFactory validatorFactory;

    @Inject
    private ConstraintViolationTranslator violationTranslator;

    @Inject
    private MvcContext mvcContext;

    @Inject
    private BindingResultImpl bindingResult;

    @AroundInvoke
    public Object validateMethodInvocation(InvocationContext ctx) throws Exception {

        Object resource = ctx.getTarget();
        Method method = ctx.getMethod();

        log.log(Level.FINE, "Starting validation for controller method: {0}#{1}", new Object[]{
                resource.getClass().getName(), method.getName()
        });

        Validator validator = validatorFactory.getValidator();
        ExecutableValidator executableValidator = validator.forExecutables();

        // validate controller property parameters
        processViolations(ctx,
                validator.validate(resource)
        );

        // validate controller method parameters
        processViolations(ctx,
                executableValidator.validateParameters(resource, method, ctx.getParameters())
        );

        // execute method
        Object result = ctx.proceed();

        // TODO: Does this make sense? Nobody will be able to handle these. Remove?
        processViolations(ctx,
                executableValidator.validateReturnValue(resource, method, result)
        );

        return result;

    }

    private void processViolations(InvocationContext ctx, Set<ConstraintViolation<Object>> violations) {

        // nothing to do in this case
        if (violations.isEmpty()) {
            return;
        }

        // create validation errors
        Set<ValidationError> validationErrors = violations.stream()
                .map(v -> createValidationError(v))
                .collect(Collectors.toSet());

        // update BindingResult
        if (!validationErrors.isEmpty()) {
            log.log(Level.FINE, "Adding {0} validation errors to binding result", validationErrors.size());
            bindingResult.addValidationErrors(validationErrors);
        }

    }

    private ValidationErrorImpl createValidationError(ConstraintViolation<?> violation) {

        ConstraintViolationMetadata metadata = ConstraintViolations.getMetadata(violation);

        String paramName = metadata.getParamName().orElse(null);
        if (paramName == null) {
            log.log(Level.WARNING, "Cannot resolve paramName for violation: {0}", violation);
        }

        String message = violationTranslator.translate(violation, mvcContext.getLocale());

        return new ValidationErrorImpl(violation, paramName, message);

    }

    private String buildExceptionMessage(Method method, Object[] args,
                                         Set<? extends ConstraintViolation<?>> violations) {

        StringBuilder message = new StringBuilder(400);
        message.append(violations.size());
        message.append(" constraint violation(s) occurred during method invocation.");
        message.append("\nMethod: ");
        message.append(method);
        message.append("\nArgument values: ");
        message.append(Arrays.toString(args));
        message.append("\nConstraint violations: ");

        int i = 1;
        for (ConstraintViolation<?> violation : violations) {
            message.append("\n (");
            message.append(i);
            message.append(") Message: ");
            message.append(violation.getMessage());
            i++;
        }

        return message.toString();
    }

}
