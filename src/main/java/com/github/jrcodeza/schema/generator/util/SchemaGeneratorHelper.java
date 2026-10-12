package com.github.jrcodeza.schema.generator.util;

import at.aau.serg.annotations.Out;
import at.aau.serg.annotations.Unused;
import at.aau.serg.frameworks.RestFramework;
import at.aau.serg.frameworks.ValidationAnnotationProvider;
import at.aau.serg.util.SpoonUtils;
import at.aau.serg.util.Utils;
import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.apache.commons.lang3.StringUtils;
import org.javatuples.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtPackage;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.factory.TypeFactory;
import spoon.reflect.reference.CtArrayTypeReference;
import spoon.reflect.reference.CtTypeReference;

import java.lang.annotation.Annotation;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.github.jrcodeza.schema.generator.util.CommonConstants.COMPONENT_REF_PREFIX;
import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;

public class SchemaGeneratorHelper {
    private final RestFramework restFramework;
    private final ValidationAnnotationProvider validationAnnotationProvider;
    private static Logger logger = LoggerFactory.getLogger(SchemaGeneratorHelper.class);

    private final List<String> modelPackages;

    public Set<CtTypeReference<?>> referencedModelClasses = new HashSet<>();

    public SchemaGeneratorHelper(List<String> modelPackages, RestFramework restFramework, ValidationAnnotationProvider validationAnnotationProvider) {
        this.modelPackages = modelPackages;
        this.restFramework = restFramework;
        this.validationAnnotationProvider = validationAnnotationProvider;
    }

    /**
     * Creates the media type for request bodies and responses. Fixme: Only works for complex types!
     *
     * @param parameterType the type of the request body or response
     * @param parameterName its name
     * @return
     */
    public MediaType createMediaType(CtTypeReference<?> parameterType,
                                     String parameterName,
                                     @Out AtomicBoolean isOptionalParameter) {
        Schema rootMediaSchema = parseSchema(parameterType, parameterName, isOptionalParameter);

        if (rootMediaSchema == null) {
            return null;
        }
        else {
            MediaType mediaType = new MediaType();
            mediaType.setSchema(rootMediaSchema);
            return mediaType;
        }
    }

    public Schema parseSchema(CtTypeReference<?> parameterType,
                                 String parameterName,
                                 @Out AtomicBoolean isOptionalParameter){
        List<CtTypeReference<?>> genericParams = this.getGenericParams(parameterType);

        if (this.isTypeEquivalent(parameterType, this.restFramework.getAsyncResultWrapper())) {
            // strip DeferredResult Spring wrapper, potentially containing everything
            var resultPair = this.unwrapGenericWrapper(parameterType, genericParams);
            parameterType = resultPair.getValue0();
            genericParams = resultPair.getValue1();

            if (parameterType == null) {
                return null;
            }
        }

        if (this.isTypeEquivalent(parameterType, this.restFramework.getResponseWrapper())) {
            var resultPair = unwrapGenericWrapper(parameterType, genericParams);
            parameterType = resultPair.getValue0();
            genericParams = resultPair.getValue1();

            if (parameterType == null) {
                return null;
            }
        }

        if (this.isTypeEquivalent(parameterType, this.restFramework.getOptionalWrapper())) {
            var resultPair = unwrapGenericWrapper(parameterType, genericParams);
            parameterType = resultPair.getValue0();
            genericParams = resultPair.getValue1();

            if (isOptionalParameter != null)
                isOptionalParameter.set(true);

            if (parameterType == null) {
                return null;
            }
        }

        Schema<?> rootMediaSchema = new Schema<>();

        if (isFile(parameterType)) {
            Schema<?> fileSchema = new Schema<>();
            fileSchema.setType("string");
            fileSchema.setFormat("binary");

            if (parameterName == null) {
                rootMediaSchema = fileSchema;
            } else {
                Map<String, Schema> properties = new HashMap<>();
                properties.put(parameterName, fileSchema);
                rootMediaSchema.setType("object");
                rootMediaSchema.setProperties(properties);
            }

        } else if (isTypeEquivalent(parameterType, Set.class)) {
            rootMediaSchema = parseArraySignature(getGenericParamAt(parameterType, 0), null, new Annotation[]{});
            rootMediaSchema.setUniqueItems(true);

        } else if (isCollection(parameterType)) {
            rootMediaSchema = parseArraySignature(getGenericParamAt(parameterType, 0), null, new Annotation[]{});

        } else if (parameterType instanceof CtArrayTypeReference<?>) {
            rootMediaSchema = parseArraySignature(((CtArrayTypeReference<?>) parameterType).getComponentType(), null, new Annotation[]{});

        } else if (isTypeEquivalent(parameterType, Map.class)) {
            rootMediaSchema = parseDictSignature(getGenericParamAt(parameterType, 1), new Annotation[]{});

        } else if (!StringUtils.equalsIgnoreCase(parameterType.getSimpleName(), "void")) {
            rootMediaSchema = parseClassRefTypeSignature(parameterType, new Annotation[]{});

        } else {
            // void
            return null;
        }

        return rootMediaSchema;
    }

    /**
     * Prepares the $ref field value, i.e., adds components/schemas as prefix to the name.
     * Additionally, the model name is stored for later analysis.
     *
     * @param modelClassName
     * @return
     */
    public String prepareSchemaReference(CtTypeReference<?> modelClassName) {
        referencedModelClasses.add(modelClassName);
        return COMPONENT_REF_PREFIX + modelClassName.getSimpleName();
    }


    private CtTypeReference<?> getFirstOrNull(List<CtTypeReference<?>> genericParams) {
        if (Utils.isEmpty(genericParams)) { // || genericParams.get(0).isAssignableFrom(List.class)) {
            return null;
        }
        return genericParams.get(0);
    }

    private boolean isCollection(CtTypeReference<?> type) {
        return isTypeEquivalent(type, Collection.class);
    }

    /**
     * Strips the framework's response wrapper if it exists,
     * otherwise returns the {@code type}.
     *
     * @param type
     * @param genericTypes
     * @return
     */
    private CtTypeReference<?> tryUnwrapFrameworkWrapper(CtTypeReference<?> type, List<CtTypeReference<?>> genericTypes) {
        if (this.isTypeEquivalent(type, this.restFramework.getResponseWrapper())) {
            return unwrapGenericWrapper(type, genericTypes).getValue0();
        }
        return type; // If no known wrapper is found, return the original type
    }

    /**
     * Strips the outermost type and returns the first generic type, or null if the outermost type was not parameterized.
     *
     * @param type
     * @param genericTypes
     * @return
     */
    private Pair<CtTypeReference<?>, List<CtTypeReference<?>>> unwrapGenericWrapper(@Unused CtTypeReference<?> type, List<CtTypeReference<?>> genericTypes) {
        if (!Utils.isEmpty(genericTypes)) {
            return new Pair<>(
                    genericTypes.get(genericTypes.size() - 1),
                    getGenericParams(genericTypes.get(genericTypes.size() - 1))
            );
        } else {
            return new Pair<>(null, null);
        }
    }

    public boolean isFile(CtTypeReference<?> type) {
        return type.isSubtypeOf(new TypeFactory().get(restFramework.getSupportedFileType()).getReference());
    }

    public Schema parseBaseTypeSignature(CtTypeReference<?> type) {
        return parseBaseTypeSignature(type, new Annotation[0]);
    }

    @SuppressWarnings("squid:S1192") // better in-place defined for better readability
    public Schema parseBaseTypeSignature(CtTypeReference<?> type, Annotation[] annotations) {
//        type.isSubtypeOf(new TypeFactory().get(byte.class).getReference())
        String typeName = type.getSimpleName();
        if (typeName.equals("byte") || typeName.equals("short") || typeName.equals("int")) {
            return createNumberSchema("integer", "int32", annotations);
        } else if (typeName.equals("long")) {
            return createNumberSchema("integer", "int64", annotations);
        } else if (typeName.equals("float")) {
            return createNumberSchema("number", "float", annotations);
        } else if (typeName.equals("double")) {
            return createNumberSchema("number", "double", annotations);
        } else if (typeName.equals("char")) {
            return createStringSchema(null, annotations);
        } else if (typeName.equals("boolean")) {
            return createBooleanSchema();
        }
        logger.info("Ignoring unsupported type=[{}]", type.getSimpleName());
        return null;
    }

    public Schema parseClassRefTypeSignature(CtTypeReference<?> typeClass) {
        return this.parseClassRefTypeSignature(typeClass, new Annotation[0]);
    }

    @SuppressWarnings("squid:S3776") // no other solution
    public Schema parseClassRefTypeSignature(CtTypeReference<?> typeClass,
                                             Annotation[] annotations) {
        return this.parseClassRefTypeSignature(typeClass, annotations, this.modelPackages);
    }

    public Schema parseClassRefTypeSignature(CtTypeReference<?> typeClass,
                                             Annotation[] annotations,
                                             List<String> modelPackages) {
        return parseClassRefTypeSignature(typeClass, annotations, modelPackages, false);
    }

    @SuppressWarnings("squid:S3776") // no other solution
    public Schema parseClassRefTypeSignature(CtTypeReference<?> typeClass,
                                             Annotation[] annotations,
                                             List<String> modelPackages,
                                             boolean forcePrimitiveType) {
        // unwrap Optional first
        if (this.isTypeEquivalent(typeClass, this.restFramework.getOptionalWrapper())) {
            typeClass = unwrapGenericWrapper(typeClass, getGenericParams(typeClass)).getValue0();
        }

        if (typeClass == null) {
            return createUnspecifiedSchema();
        }

        Schema resultSchema = null;

        String typeName = typeClass.getSimpleName();
        if (typeName.equals("Byte") || typeName.equals("Short") || typeName.equals("Integer")) {
            resultSchema = createNumberSchema("integer", "int32", annotations);
        } else if (typeName.equals("Long") || typeName.equals("BigInteger")) {
            resultSchema = createNumberSchema("integer", "int64", annotations);
        } else if (typeName.equals("Float")) {
            resultSchema = createNumberSchema("number", "float", annotations);
        } else if (typeName.equals("Double") || typeName.equals("BigDecimal")) {
            resultSchema = createNumberSchema("number", "double", annotations);
        } else if (typeName.equals("Character") || typeName.equals("String")) {
            resultSchema = createStringSchema(null, annotations);
        } else if (typeName.equals("Boolean")) {
            resultSchema = createBooleanSchema();
        } else if (typeName.equals("LocalDate")) {
            resultSchema = createStringSchema("date", annotations);
        } else if (typeName.equals("Instant") || typeName.equals("LocalDateTime") || typeName.equals("OffsetDateTime")
                || typeName.equals("ZonedDateTime") || typeName.equals("LocalTime") || typeName.equals("Date")) {
            resultSchema = createStringSchema("date-time", annotations);
        } else if (typeName.equals("URI") || typeName.equals("URL")) {
            resultSchema = createStringSchema("uri", annotations);
        } else if (typeName.equals("UUID")) {
            resultSchema = createStringSchema("uuid", annotations);
        } else if (typeName.equals("Object")) {
            resultSchema = createObjectSchema();
        } else {
            resultSchema = createRefSchema(typeClass, modelPackages);
        }

        if (forcePrimitiveType) {
            if (resultSchema.get$ref() != null && !typeClass.isEnum()) {
                // complex schema not allowed here
                resultSchema.setType("string");
                resultSchema.setExample(resultSchema.get$ref());
                resultSchema.set$ref(null);
            }
        }

        return resultSchema;
    }

    public Schema parseArraySignature(CtTypeReference<?> elementTypeSignature,
                                      Annotation[] annotations) {
        return this.parseArraySignature(elementTypeSignature, this.modelPackages, annotations);
    }

    public Schema parseArraySignature(CtTypeReference<?> elementTypeSignature,
                                      List<String> modelPackages,
                                      Annotation[] annotations) {
        ArraySchema arraySchema = new ArraySchema();
        if (elementTypeSignature == null) {
            arraySchema.setItems(createObjectSchema());
            return arraySchema;
        }

        enrichWithTypeAnnotations(arraySchema, annotations);
        Stream.of(annotations).forEach(annotation -> applyArrayAnnotations(arraySchema, annotation));

        if (elementTypeSignature.isPrimitive()) {
            // primitive type like int
            Schema itemSchema = parseBaseTypeSignature(elementTypeSignature);
            arraySchema.setItems(itemSchema);
            return arraySchema;

        } else {
            Schema itemSchema = parseSchema(elementTypeSignature, null, null);
            arraySchema.setItems(itemSchema);
            return arraySchema;
        }
    }

    public Schema parseDictSignature(CtTypeReference<?> elementTypeSignature,
                                     Annotation[] annotations) {
        return this.parseDictSignature(elementTypeSignature, this.modelPackages, annotations);
    }

    public Schema parseDictSignature(CtTypeReference<?> elementTypeSignature,
                                     List<String> modelPackages,
                                     Annotation[] annotations) {
        Schema dictSchema = new Schema();
        dictSchema.setType("object");

        if (elementTypeSignature == null) {
            dictSchema.setAdditionalProperties(createObjectSchema());
            return dictSchema;
        }

        enrichWithTypeAnnotations(dictSchema, annotations);

        if (elementTypeSignature.isPrimitive()) {
            // primitive type like int
            Schema addPropSchema = parseBaseTypeSignature(elementTypeSignature);
            dictSchema.setAdditionalProperties(addPropSchema);
            return dictSchema;

        } else {
            Schema addPropSchema = parseSchema(elementTypeSignature, null, null);
            dictSchema.setAdditionalProperties(addPropSchema);
            return dictSchema;
        }
    }

    /**
     * Creates a special schema for unspecified types, eg ResponseEntity or ResponseEntity<\?>.
     *
     * @return
     */
    public Schema<?> createUnspecifiedSchema() {
        Schema<?> schema = new Schema<>();
        schema.setType("object");
        schema.setExternalDocs(new ExternalDocumentation()
                .url("unspecified") // mandatory OpenAPI property
                .description("Unspecified return type, e.g., ResponseEntity<?>") //, Response
        );

        return schema;
    }

    private Schema<?> createObjectSchema() {
        Schema<?> schema = new Schema<>();
        schema.setType("object");
        return schema;
    }

    @SuppressWarnings("squid:S1192") // better in-place defined for better readability
    protected Schema createBooleanSchema() {
        Schema<?> schema = new Schema<>();
        schema.setType("boolean");
        return schema;
    }

    @SuppressWarnings("squid:S1192") // better in-place defined for better readability
    protected Schema createStringSchema(String format, Annotation[] annotations) {
        Schema<?> schema = new Schema<>();
        schema.setType("string");
        if (StringUtils.isNotBlank(format)) {
            schema.setFormat(format);
        }
        if (annotations != null) {
            asList(annotations).forEach(annotation -> applyStringAnnotations(schema, annotation));
        }
        return schema;
    }

    public StringSchema createEnumSchema(Stream<String> enumConstants) {
        StringSchema schema = new StringSchema();
        schema.setType("string");
        schema.setEnum(enumConstants.collect(Collectors.toList()));
        return schema;
    }

    protected Schema createNumberSchema(String type, String format, Annotation[] annotations) {
        Schema<?> schema = new Schema<>();
        schema.setType(type);
        schema.setFormat(format);
        asList(annotations).forEach(annotation -> applyNumberAnnotation(schema, annotation));
        return schema;
    }

    protected ComposedSchema createRefSchema(CtTypeReference<?> typeSignature, @Unused List<String> modelPackages) {
        ComposedSchema composedSchema = new ComposedSchema();

        composedSchema.set$ref(prepareSchemaReference(typeSignature));
        return composedSchema;
    }

    protected void applyStringAnnotations(Schema<?> schema, Annotation annotation) {
        if (annotation.annotationType() != null) {
            validationAnnotationProvider.getPatternRegexpIfPresent(annotation)
                    .ifPresent(schema::pattern);

            validationAnnotationProvider.getSizeMinIfPresent(annotation)
                    .ifPresent(schema::minLength);

            validationAnnotationProvider.getSizeMaxIfPresent(annotation)
                    .ifPresent(schema::maxLength);
        }
    }

    protected void applyNumberAnnotation(Schema<?> schema, Annotation annotation) {
        if (annotation.annotationType() != null) {
            validationAnnotationProvider.getDecimalMinValueIfPresent(annotation)
                    .ifPresent(value -> schema.setMinimum(new BigDecimal(value)));

            validationAnnotationProvider.getDecimalMaxValueIfPresent(annotation)
                    .ifPresent(value -> schema.setMaximum(new BigDecimal(value)));

            validationAnnotationProvider.getMinValueIfPresent(annotation)
                    .ifPresent(value -> schema.setMinimum(new BigDecimal(value)));

            validationAnnotationProvider.getMaxValueIfPresent(annotation)
                    .ifPresent(value -> schema.setMaximum(new BigDecimal(value)));
        }
    }

    protected void applyArrayAnnotations(ArraySchema schema, Annotation annotation) {
        if (annotation.annotationType() != null) {
            validationAnnotationProvider.getSizeMinIfPresent(annotation)
                    .ifPresent(schema::minItems);

            validationAnnotationProvider.getSizeMaxIfPresent(annotation)
                    .ifPresent(schema::maxItems);
        }
    }

    public boolean isInPackagesToBeScanned(CtTypeReference<?> clazz, List<String> modelPackages) {
        return isInPackagesToBeScanned(clazz.getTypeDeclaration(), modelPackages);
    }

    public boolean isInPackagesToBeScanned(CtTypeReference<?> clazz) {
        return isInPackagesToBeScanned(clazz.getTypeDeclaration());
    }

    public boolean isInPackagesToBeScanned(CtType<?> clazz) {
        return isInPackagesToBeScanned(clazz, this.modelPackages);
    }

    public boolean isInPackagesToBeScanned(CtType<?> clazz, List<String> modelPackages) {
        return modelPackages == null
                || modelPackages.stream().anyMatch(pkg -> {
                    CtPackage clazzPackage = clazz.getPackage();
                    if (clazzPackage != null) {
                        return clazzPackage.getQualifiedName().equals(pkg);
                    }
                    return false;
                }
        );
    }

    public void enrichWithTypeAnnotations(Schema<?> schema, Annotation[] annotations) {
        enrichWithAnnotation(io.swagger.v3.oas.annotations.media.Schema.class, annotations,
                schemaAnnotation -> {
                    schema.setDeprecated(schemaAnnotation.deprecated());
                    schema.setDescription(schemaAnnotation.description());
                    enrichWithAccessMode(schema, schemaAnnotation);
                });
        enrichWithAnnotation(Deprecated.class, annotations, deprecatedAnnotation -> schema.setDeprecated(true));
        enrichWithAnnotation(com.fasterxml.jackson.annotation.JsonProperty.class, annotations, property -> {
            var access = property.access();
            if ((access == com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY
                    && Boolean.TRUE.equals(schema.getWriteOnly()))
                    || (access == com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY
                    && Boolean.TRUE.equals(schema.getReadOnly()))
                    || (access == com.fasterxml.jackson.annotation.JsonProperty.Access.READ_WRITE
                    && (Boolean.TRUE.equals(schema.getReadOnly()) || Boolean.TRUE.equals(schema.getWriteOnly())))) {
                throw new IllegalArgumentException("Conflicting Jackson/OpenAPI property access");
            }
            if (access == com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY) schema.setReadOnly(true);
            if (access == com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY) schema.setWriteOnly(true);
        });
    }

    public void enrichWithTypeAnnotations(Parameter parameter, Annotation[] annotations) {
        enrichWithAnnotation(io.swagger.v3.oas.annotations.media.Schema.class, annotations,
                schemaAnnotation -> {
                    parameter.setDeprecated(schemaAnnotation.deprecated());
                    parameter.setDescription(schemaAnnotation.description());
                });
        enrichWithAnnotation(Deprecated.class, annotations, deprecatedAnnotation -> parameter.setDeprecated(true));
    }

    private void enrichWithAccessMode(Schema<?> schema, io.swagger.v3.oas.annotations.media.Schema schemaAnnotation) {
        if (schemaAnnotation.accessMode() == io.swagger.v3.oas.annotations.media.Schema.AccessMode.READ_ONLY) {
            schema.setReadOnly(true);
        } else if (schemaAnnotation.accessMode() == io.swagger.v3.oas.annotations.media.Schema.AccessMode.WRITE_ONLY) {
            schema.setWriteOnly(true);
        }
    }

    protected <T> void enrichWithAnnotation(Class<T> annotationClazz, Annotation[] annotations, Consumer<T> consumer) {
        Stream.of(annotations)
                .filter(annotation -> annotationClazz.isAssignableFrom(annotation.getClass()))
                .map(annotationClazz::cast)
                .findFirst()
                .ifPresent(consumer);
    }

    public List<CtTypeReference<?>> getGenericParams(CtTypeReference<?> methodType) {
        if (methodType.isParameterized()) {
            List<CtTypeReference<?>> typeArguments = methodType.getActualTypeArguments();
//			if (typeArguments != null && typeArguments.length > 0)
            {
                CtTypeReference<?> typeArgument = typeArguments.get(0);
                if (typeArgument.isClass()) {
                    return singletonList(typeArgument);

                } else if (typeArgument.isParameterized()) {
                    // e.g., List<SomeClass>
                    var innerTypes = typeArgument.getActualTypeArguments();
                    return innerTypes.size() > 0
                            ? asList(innerTypes.get(0), typeArgument)
                            : null;

                } else if (typeArgument.isInterface()) {
                    // e.g., DtoInterface [without generic type]
                    return singletonList(typeArgument);
                }
            }
        }
        return null;
    }

    @Deprecated
    public CtTypeReference<?> getGenericParam(CtParameter<?> parameter) {
        return getGenericParamAt(parameter.getType(), 0);

//        if (parameter.getType().getActualTypeArguments().size() > 0) {
//            CtTypeReference<?> parameterizedType = parameter.getType().getActualTypeArguments().get(0);
//            return parameterizedType;
//        }
//        return null;
    }

    public CtTypeReference<?> getGenericParam(CtTypeReference<?> type) {
        // idx = 0 required for getGenericParam(CtParameter) which was migrated to this call
        return getGenericParamAt(type, 0);
    }

    public CtTypeReference<?> getGenericParamAt(CtTypeReference<?> type, int idx) {
        if (type.getActualTypeArguments().size() > idx) {
            CtTypeReference<?> parameterizedType = type.getActualTypeArguments().get(idx);
            return parameterizedType;
        }
        return null;
    }

    /**
     * Also accepts subtypes
     *
     * @param ctType
     * @param type
     * @return
     */
    public boolean isTypeEquivalent(CtTypeReference<?> ctType, Class type) {
        return SpoonUtils.isTypeEquivalent(ctType, type);
    }

    /**
     * Return an empty Annotation for each Annotation which cannot be parsed
     *
     * @param annotations
     * @return
     */
    public Annotation[] getActualAnnotations(List<CtAnnotation<? extends Annotation>> annotations) {
        return annotations.stream().map(a -> {
                    try {
                        return a.getActualAnnotation();
                    } catch (Exception e) {
                        return (Annotation) () -> null;
                    }
                }
        ).toArray(Annotation[]::new);
    }

    /**
     * Returns True if the annotations contain a @JsonIgnore annotation.
     * @param annotations
     * @return
     */
    public boolean hasJsonIgnore(Annotation[] annotations) {
        for (Annotation annotation : annotations) {
            if (annotation instanceof com.fasterxml.jackson.annotation.JsonIgnore ignore && ignore.value()) {
                return true;
            }
        }
        return false;
    }
}
