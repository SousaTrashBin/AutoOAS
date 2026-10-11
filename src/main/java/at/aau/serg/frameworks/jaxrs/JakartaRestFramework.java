package at.aau.serg.frameworks.jaxrs;

import at.aau.serg.frameworks.*;
import at.aau.serg.frameworks.jaxrs.adapters.mappings.JakartaRestOperationAnnotationAdapter;
import at.aau.serg.frameworks.jaxrs.adapters.parameters.JakartaHeaderParamAdapter;
import at.aau.serg.frameworks.jaxrs.adapters.parameters.JakartaPathParamAdapter;
import at.aau.serg.frameworks.jaxrs.adapters.parameters.JakartaQueryParamAdapter;
import at.aau.serg.interceptors.JakartaOperationResponseCodeInterceptor;
import at.aau.serg.parsers.HttpMethod;
import at.aau.serg.util.SpoonUtils;
import com.github.jrcodeza.schema.generator.DataTypeTransformer;
import com.github.jrcodeza.schema.generator.MethodResponseExtractor;
import com.github.jrcodeza.schema.generator.interceptors.OperationInterceptor;
import com.github.jrcodeza.schema.generator.util.SchemaGeneratorHelper;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static at.aau.serg.frameworks.utils.AnnotationUtils.getAnnotation;

public class JakartaRestFramework extends AbstractJaxRsFramework {

    @Override
    public String getIdentifier() {
        return super.getIdentifier() + " (Jakarta EE)";
    }

    @Override
    public OperationInterceptor getOperationResponseCodeInterceptor(List<CtType<?>> globalExceptionHandlerClasses,
                                                                    DataTypeTransformer dataTypeTransformer,
                                                                    SchemaGeneratorHelper schemaHelper,
                                                                    MethodResponseExtractor methodResponseExtractor) {
        return new JakartaOperationResponseCodeInterceptor(globalExceptionHandlerClasses, dataTypeTransformer, schemaHelper, methodResponseExtractor, exceptionLoggingEnabled);
    }

    @Override
    public List<String> getControllerAnnotations() {
        return List.of("jakarta.ws.rs.Path");
    }

    /**
     * An exception contains @Provider as annotation and also
     * implements the jakarta.ws.rs.ext.ExceptionMapper
     */
    @Override
    public List<String> getControllerAdviceAnnotations() {
        return List.of("jakarta.ws.rs.ext.Provider");
    }

    @Override
    public String getApplicationPathAnnotation() {
        return "jakarta.ws.rs.ApplicationPath";
    }

    @Override
    public Set<String> getKeyAnnotations() {
        return Set.of("jakarta.ws.rs.Path");
    }

    @Override
    public boolean isGlobalExceptionHandler(String annotationName, CtType<?> type) {
        if (getControllerAdviceAnnotations().contains(annotationName)) {
            for (CtTypeReference<?> interfaceRef : type.getSuperInterfaces()) {
                String qualifiedName = interfaceRef.getQualifiedName();
                if (qualifiedName.equals("jakarta.ws.rs.ext.ExceptionMapper")) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean isApplicationPath(String annotationName, CtType<?> type) {
        if(getApplicationPathAnnotation().equals(annotationName)) {
            CtTypeReference<?> superclassRef = type.getSuperclass();

            if(superclassRef != null) {
                String qualifiedName = superclassRef.getQualifiedName();
                return qualifiedName.equals("jakarta.ws.rs.core.Application");
            }
        }
        return false;
    }

    @Override
    public Class<?> getResponseWrapper() {
        return Response.class;
    }

    @Override
    public Class<?> getSupportedFileType() {
        return MultivaluedMap.class;
    }

    @Override
    public Class<? extends Annotation> getParameterGroupAnnotation() {
        return BeanParam.class;
    }

    /**
     * TODO de-duplicate this and JavaxRestFramework::getSubResourcesInController
     *
     * @param controllerType
     * @return
     */
    @Override
    public List<SubResource> getSubResourcesInController(CtType<?> controllerType) {
        var resultList = new ArrayList<SubResource>();

        for (CtMethod<?> method : controllerType.getMethods()) {
            CtType<?> methodReturnType = method.getType().getTypeDeclaration();
            Optional<Path> pathAnnotation = getAnnotation(method, Path.class);

            if (pathAnnotation.isEmpty()
                    || !classContainsHandlerMethodOrSubResource(methodReturnType)  // then its most probably a data class
                    || SpoonUtils.areTypesEqual(controllerType, methodReturnType)) // then its a recursive path providing the same class again
                continue;

            // found a method with Path annotation providing a sub-resource
            String subResourcePath = pathAnnotation.get().value();
            resultList.add(new SubResource(methodReturnType, subResourcePath, method));
        }

        return resultList;
    }

    @Override
    public CtParameter<?> findRequestBody(List<CtParameter<?>> parameters) {
        return parameters.stream()
                .filter(param -> !hasAnyAnnotationDisqualifyingParameterAsRequestBody(param))
                .findFirst()
                .orElse(null);
    }

    @Override
    public String getFormPropertyName(CtParameter<?> parameter) {
        return parameter.getAnnotation(jakarta.ws.rs.FormParam.class).value();
    }

    @Override
    public boolean requiresMultipart(CtParameter<?> parameter) {
        return false;
    }

    @Override
    public String getFormParamAnnotationName() {
        return "jakarta.ws.rs.FormParam";
    }

    @Override
    public Optional<? extends Annotation> findPostMappingAnnotation(CtMethod<?> method) {
        return getAnnotation(method, POST.class);
    }

    @Override
    public Optional<? extends Annotation> findPutMappingAnnotation(CtMethod<?> method) {
        return getAnnotation(method, PUT.class);
    }

    @Override
    public Optional<? extends Annotation> findPatchMappingAnnotation(CtMethod<?> method) {
        return getAnnotation(method, PATCH.class);
    }

    @Override
    public Optional<? extends Annotation> findGetMappingAnnotation(CtMethod<?> method) {
        return getAnnotation(method, GET.class);
    }

    @Override
    public Optional<? extends Annotation> findDeleteMappingAnnotation(CtMethod<?> method) {
        return getAnnotation(method, DELETE.class);
    }


    @Override
    public Optional<RestOperationAnnotation> findClassRequestMappingAnnotation(CtType<?> clazz) {
        for (CtAnnotation<?> annotation : clazz.getAnnotations()) {
            String annotationTypeName = annotation.getAnnotationType().getQualifiedName();
            if (annotationTypeName.equals(Path.class.getName())) {
                return Optional.of(new JakartaRestOperationAnnotationAdapter(clazz));
            }
        }
        return Optional.empty();
    }

    @Override
    public RestOperationAnnotation convertToRequestAnnotation(Annotation annotation, CtMethod<?> method) {
        if (annotation instanceof POST) {
            return new JakartaRestOperationAnnotationAdapter(method, HttpMethod.POST);
        }
        if (annotation instanceof PUT) {
            return new JakartaRestOperationAnnotationAdapter(method, HttpMethod.PUT);
        }
        if (annotation instanceof PATCH) {
            return new JakartaRestOperationAnnotationAdapter(method, HttpMethod.PATCH);
        }
        if (annotation instanceof GET) {
            return new JakartaRestOperationAnnotationAdapter(method, HttpMethod.GET);
        }
        if (annotation instanceof DELETE) {
            return new JakartaRestOperationAnnotationAdapter(method, HttpMethod.DELETE);
        }

        throw new IllegalArgumentException("No supported annotation found");
    }

    @Override
    public PathVariableAnnotation tryConvertPathVariableAnnotation(CtParameter<?> parameter) {
        PathParam annotation = parameter.getAnnotation(PathParam.class);
        if (annotation != null) {
            return new JakartaPathParamAdapter(annotation);
        }

        return null;
    }

    @Override
    public RequestParamAnnotation tryConvertRequestParamAnnotation(CtParameter<?> parameter) {
        // Checks if NotEmpty or NotNull annotation exists and if parameter is type String.
        // int/long/double are required in OpenAPI for NotNull but not in an actual request as they have a default value
        boolean isParameterRequired = hasValidationAnnotation(parameter) && isParameterTypeString(parameter);

        QueryParam annotation = parameter.getAnnotation(QueryParam.class);
        if (annotation != null) {
            JakartaQueryParamAdapter adapter = new JakartaQueryParamAdapter(annotation);
            adapter.setRequired(isParameterRequired);
            return adapter;
        }

        return null;
    }

    @Override
    public RequestHeaderAnnotation tryConvertRequestHeaderAnnotation(CtParameter<?> parameter) {
        boolean isParameterRequired = hasValidationAnnotation(parameter) && isParameterTypeString(parameter);

        HeaderParam annotation = parameter.getAnnotation(HeaderParam.class);
        if (annotation != null) {
            JakartaHeaderParamAdapter adapter = new JakartaHeaderParamAdapter(annotation);
            adapter.setRequired(isParameterRequired);
            return adapter;
        }

        return null;
    }

    @Override
    public MultipartParameterAnnotation tryConvertRequestPartAnnotation(CtParameter<?> parameter) {
        return null;
    }

    private boolean hasValidationAnnotation(CtParameter<?> parameter) {
        Set<String> requiredAnnotations = Set.of(
                "jakarta.validation.constraints.NotNull",
                "jakarta.validation.constraints.NotEmpty",
                "jakarta.validation.constraints.NotBlank",
                "javax.validation.constraints.NotNull",
                "javax.validation.constraints.NotEmpty",
                "javax.validation.constraints.NotBlank");
        return parameter.getAnnotations().stream()
                .map(annotation -> annotation.getAnnotationType().getQualifiedName())
                .anyMatch(requiredAnnotations::contains);
    }

    /**
     * Returns true if parameter should be disqualified as a request body.
     * Disqualifies if parameter has JAX-RS binding annotations (@PathParam, @QueryParam, @Context, etc.)
     * or if parameter type is a framework-injected type (SecurityContext, HttpHeaders, UriInfo)
     * In Quarkus, framework-injected types can have @Context but can also be used without this annotation
     */
    private boolean hasAnyAnnotationDisqualifyingParameterAsRequestBody(CtParameter<?> parameter) {
        return isRestMethodAnnotatedParameter(parameter) || isJakartaConfigurationAnnotation(parameter);
    }

    private boolean isJakartaConfigurationAnnotation(CtParameter<?> parameter) {
        String typeName = parameter.getType().getQualifiedName();
        return typeName.equals("jakarta.ws.rs.core.SecurityContext") ||
                typeName.equals("jakarta.ws.rs.core.UriInfo") ||
                typeName.equals("jakarta.ws.rs.core.HttpHeaders");
    }

    @Override
    protected boolean isRestParameterAnnotation(String annotationName) {
        return annotationName.equals("jakarta.ws.rs.PathParam") ||
                annotationName.equals("jakarta.ws.rs.FormParam") ||
                annotationName.equals("jakarta.ws.rs.QueryParam") ||
                annotationName.equals("jakarta.ws.rs.HeaderParam") ||
                annotationName.equals("jakarta.ws.rs.CookieParam") ||
                annotationName.equals("jakarta.ws.rs.MatrixParam") ||
                annotationName.equals("jakarta.ws.rs.BeanParam") ||
                annotationName.equals("jakarta.ws.rs.container.Suspended") ||
                annotationName.equals("jakarta.ws.rs.core.Context");
    }

    @Override
    protected boolean isRestHandlerMethodOrSubResourceAnnotation(String annotationName) {
        return annotationName.equals("jakarta.ws.rs.POST") ||
                annotationName.equals("jakarta.ws.rs.PUT") ||
                annotationName.equals("jakarta.ws.rs.PATCH") ||
                annotationName.equals("jakarta.ws.rs.GET") ||
                annotationName.equals("jakarta.ws.rs.DELETE") ||
                annotationName.equals("jakarta.ws.rs.HEAD") ||
                annotationName.equals("jakarta.ws.rs.OPTIONS") ||
                annotationName.equals("jakarta.ws.rs.Path");
    }
}
