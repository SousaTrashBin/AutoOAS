package com.github.jrcodeza.schema.generator;

import at.aau.serg.frameworks.RestFramework;
import com.github.jrcodeza.schema.generator.util.SchemaGeneratorHelper;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.factory.TypeFactory;
import spoon.reflect.reference.CtArrayTypeReference;
import spoon.reflect.reference.CtTypeReference;

import java.lang.annotation.Annotation;
import java.util.List;

public class DataTypeTransformer {

    public static final String UNSPECIFIED_SIMPLE_NAME = "UNSPECIFIED_TYPE";

    public static CtTypeReference<?> CREATE_UNSPECIFIED_RESPONSE_TYPE() {
        var responseType = new TypeFactory().OMITTED_TYPE_ARG_TYPE;
        responseType.setSimpleName(UNSPECIFIED_SIMPLE_NAME);
        return responseType;
    }

    private static final String DEFAULT_CONTENT_TYPE = "application/json";
    private static final String DEFAULT_FILE_RETURN_CONTENT_TYPE = "application/octet-stream";
    private static final String MULTIPART_FORM_DATA_CONTENT_TYPE = "multipart/form-data";

    private static Logger logger = LoggerFactory.getLogger(OperationsTransformer.class);

    private RestFramework restFramework;
    private SchemaGeneratorHelper schemaGeneratorHelper;

    public DataTypeTransformer(RestFramework restFramework, SchemaGeneratorHelper schemaGeneratorHelper) {
        this.restFramework = restFramework;
        this.schemaGeneratorHelper = schemaGeneratorHelper;
    }

    public ApiResponse detectAndCreateApiResponseContent(CtTypeReference<?> responseType) {
        return detectAndCreateApiResponseContent(responseType, null);
    }

    // TODO alex: use this inside the interceptor for finding the type in .entity or .ok
    public ApiResponse detectAndCreateApiResponseContent(CtTypeReference<?> responseType, String produces) {
        ApiResponse apiResponse = new ApiResponse();

        // content

        // strip the DeferredResult wrapper
        if (restFramework.getAsyncResultWrapperNames().contains(responseType.getQualifiedName())) {
            responseType = stripReturnValueWrapper(responseType);
        }

        // strip the ResponseEntity wrapper
        if (restFramework.getResponseWrapperNames().contains(responseType.getQualifiedName())) {
            responseType = stripReturnValueWrapper(responseType);
        }

        if (schemaGeneratorHelper.isTypeEquivalent(responseType, restFramework.getOptionalWrapper())) {
            responseType = stripReturnValueWrapper(responseType);
        }

        if ("void".equals(responseType.getSimpleName()) || "Void".equals(responseType.getSimpleName())) {
            // dont add a content

        } else if (responseType.isPrimitive() ||
                (responseType.getPackage() != null && responseType.getPackage().getSimpleName().equals("java.lang"))) {
            Content content = new Content();
            MediaType simpleMediaType = new MediaType();
            simpleMediaType.setSchema(
                    responseType.isPrimitive()
                            ? schemaGeneratorHelper.parseBaseTypeSignature(responseType, new Annotation[0])
                            : schemaGeneratorHelper.parseClassRefTypeSignature(responseType, new Annotation[0], null)
            );
            content.addMediaType(StringUtils.isBlank(produces) ? resolveDefaultContentType(responseType) : stripMediaType(produces), simpleMediaType);
            apiResponse.setContent(content);

        } else {
            if (responseType.getPackage() == null
                    // arrays have no package, do not omit arrays
                    && !(responseType instanceof CtArrayTypeReference<?>)) {
                // e.g. for ? generic capture
                logger.info("Ignoring responseType {}", responseType.getSimpleName());
                responseType = CREATE_UNSPECIFIED_RESPONSE_TYPE();
            }

            MediaType mediaType = schemaGeneratorHelper.createMediaType(responseType, null, null);
            if (mediaType != null) { // mediaType might be null, e.g., if the returnType is not part of the project (e.g., java.util.Map for delete).
                Content content = new Content();
                content.addMediaType(StringUtils.isBlank(produces) ? resolveDefaultContentType(responseType) : stripMediaType(produces), mediaType);
                apiResponse.setContent(content);
            }
        }

        return apiResponse;
    }

    private String stripMediaType(String produces){
        if (produces == null)
            return null;
        return produces.split(";")[0];
    }

    /**
     * Strips the Spring return value wrappers, e.g., ResponseEntity&lt;T&gt; or DeferredResult&lt;T&gt;,
     * and returns the generic type T or OMITTED_TYPE_ARG_TYPE.
     *
     * @param methodReturnType
     * @return
     */
    private static CtTypeReference<?> stripReturnValueWrapper(CtTypeReference<?> methodReturnType) {
        // check for generic type
        if (!methodReturnType.getActualTypeArguments().isEmpty())
            methodReturnType = methodReturnType.getActualTypeArguments().get(0);
        else {
            // ignoring empty ResponseEntity capture
            methodReturnType = CREATE_UNSPECIFIED_RESPONSE_TYPE();
        }
        return methodReturnType;
    }


    private String resolveDefaultContentType(CtTypeReference<?> responseBody) {
        if (isFileResponse(responseBody)) {
            return DEFAULT_FILE_RETURN_CONTENT_TYPE;
        }
        return DEFAULT_CONTENT_TYPE;
    }


    private boolean isFileResponse(CtTypeReference<?> responseBodyClass) {
        return responseBodyClass.isSubtypeOf(new TypeFactory().get(restFramework.getSupportedFileType()).getReference());
    }

    private List<CtTypeReference<?>> getGenericParams(CtTypeReference<?> methodType) {
        return schemaGeneratorHelper.getGenericParams(methodType);
    }


    public String resolveContentType(String userDefinedContentType, CtParameter<?> requestBody) {
        if (StringUtils.isBlank(userDefinedContentType)) {
            return schemaGeneratorHelper.isFile(requestBody.getType()) ? MULTIPART_FORM_DATA_CONTENT_TYPE : DEFAULT_CONTENT_TYPE;
        }
        return userDefinedContentType;
    }
}
