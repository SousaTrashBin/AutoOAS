package at.aau.serg.parsers;

import at.aau.serg.frameworks.DetectedApiParamsAndResponses;
import at.aau.serg.frameworks.RestFramework;
import at.aau.serg.frameworks.ValidationAnnotationProvider;
import at.aau.serg.frameworks.validation.ValidationAnnotationProviderFactory;
import at.aau.serg.openapi.OpenApiGenerator;
import com.github.jrcodeza.schema.generator.ComponentSchemaTransformer;
import com.github.jrcodeza.schema.generator.DataTypeTransformer;
import com.github.jrcodeza.schema.generator.MethodResponseExtractor;
import com.github.jrcodeza.schema.generator.OperationsTransformer;
import com.github.jrcodeza.schema.generator.interceptors.OperationInterceptor;
import com.github.jrcodeza.schema.generator.model.InheritanceInfo;
import com.github.jrcodeza.schema.generator.util.SchemaGeneratorHelper;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.servers.Server;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.cu.SourcePosition;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtPackage;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;

import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class RestApiParser {
    private OperationsTransformer operationsTransformer;
    private DataTypeTransformer dataTypeTransformer;
    private ComponentSchemaTransformer schemaTransformer;
    private SchemaGeneratorHelper schemaHelper;
    private OperationInterceptor operationResponseCodeInterceptor;

    private final OpenApiGenerator openApiGen = new OpenApiGenerator();

    protected CtModel model;
    protected String projectName;
    protected String restApiModulePath;
    private String restApiModulePathWithTrailingSeparator;
    protected String outputFileName;
    private RestFramework restFramework;

    /**
     * Automatically creates a Spoon model for the project in {@code projectPath} to detect REST APIs implemented in {@code restFramework}.
     *
     * @param projectPath    The path to the project under analysis
     * @param outputFileName
     * @param restFramework
     */
    protected RestApiParser(String projectPath, String outputFileName, RestFramework restFramework) {
        this(projectPath, outputFileName, restFramework, new SpoonModelLoader().loadModel(projectPath));
    }

    /**
     * Automatically creates a Spoon model for the project in {@code projectPath}
     * to detect REST APIs implemented in {@code restFramework} inside the {@code restApiModulePath}.
     *
     * @param projectPath       The path to the project under analysis
     * @param restApiModulePath The path to the API module in the multi-module project in {@code projectPath}
     * @param outputFileName
     * @param restFramework
     */
    protected RestApiParser(String projectPath, String restApiModulePath, String outputFileName, RestFramework restFramework) {
        this(projectPath, restApiModulePath, outputFileName, restFramework, new SpoonModelLoader().loadModel(projectPath));
    }

    /**
     * Uses the Spoon model {@code model} for the project in {@code projectPath}} to detect REST APIs implemented in {@code restFramework}.
     *
     * @param projectPath    The path to the project under analysis
     * @param outputFileName
     * @param restFramework
     * @param model          The model of the project in {@code projectPath}
     */
    protected RestApiParser(String projectPath, String outputFileName, RestFramework restFramework, CtModel model) {
        this(projectPath, projectPath, outputFileName, restFramework, model);
    }

    /**
     * Uses the Spoon model {@code model} for the project in {@code projectPath}
     * to detect REST APIs implemented in {@code restFramework} inside the {@code restApiModulePath}.
     *
     * @param projectPath       The path to the project under analysis
     * @param restApiModulePath The path to the API module in the multi-module project in {@code projectPath}
     * @param outputFileName
     * @param restFramework
     * @param model             The model of the project in {@code projectPath}
     */
    protected RestApiParser(String projectPath, String restApiModulePath, String outputFileName, RestFramework restFramework, CtModel model) {
        this.projectName = projectPath.substring(projectPath.lastIndexOf('/') + 1);
        this.restApiModulePath = restApiModulePath;
        this.restApiModulePathWithTrailingSeparator = restApiModulePath.endsWith(File.separator)
                ? restApiModulePath
                : restApiModulePath + File.separator;
        this.outputFileName = outputFileName;
        this.restFramework = restFramework;

        this.model = model;
    }

    public void run() {
        generateOpenApi(model);
    }

    /**
     * Generates one OpenAPI per Spring Boot Profile from the project. Writes the OASs to file and returns them.
     *
     * @param model
     * @return
     */
    private List<OpenAPI> generateOpenApi(CtModel model) {
        var packages = model.getAllPackages();
        List<String> packageNames = packages.stream()
                .filter(p -> !p.isEmpty())
                .map(p -> p.toString())
                .collect(Collectors.toList());


        RelevantClasses relevantClasses = getRelevantClassesFromPackages(packages);
        List<CtType<?>> controllerClasses = relevantClasses.getControllerClasses();
        List<CtType<?>> globalExceptionHandlerClasses = relevantClasses.getGlobalExceptionHandlerClasses();
        List<CtType<?>> applicationPathClasses = relevantClasses.getApplicationPathClasses();
        List<CtType<?>> explicitModelClasses = relevantClasses.getExplicitModelClasses();

        List<String> basePaths = extractApplicationPathAnnotationValues(applicationPathClasses);

        ValidationAnnotationProvider annotationProvider = new ValidationAnnotationProviderFactory().getCompositeProvider();
        schemaHelper = new SchemaGeneratorHelper(packageNames, restFramework, annotationProvider); // just provide all packages of the project's module
        dataTypeTransformer = new DataTypeTransformer(restFramework, schemaHelper);
        MethodResponseExtractor methodResponseExtractor = new MethodResponseExtractor(restFramework, dataTypeTransformer);
        operationResponseCodeInterceptor = restFramework.getOperationResponseCodeInterceptor(globalExceptionHandlerClasses, dataTypeTransformer, schemaHelper, methodResponseExtractor);
        operationsTransformer = new OperationsTransformer(schemaHelper, dataTypeTransformer, methodResponseExtractor,
                new ArrayList<>(), Collections.singletonList(operationResponseCodeInterceptor),
                new ArrayList<>(), new ArrayList<>(), new AtomicReference<>(), restFramework);
        schemaTransformer = new ComponentSchemaTransformer(new ArrayList<>(), schemaHelper, annotationProvider);

        Map<String, List<CtType<?>>> controllerClassesPerProfile = restFramework.splitClassesOnProfiles(controllerClasses);
        System.out.println("Detected profiles: " + controllerClassesPerProfile.keySet());

        var res = new ArrayList<OpenAPI>(controllerClassesPerProfile.size());

        for (var profile : controllerClassesPerProfile.entrySet()) {
            OpenAPI openApiForProfile = createOpenAPIFromProfile(profile, explicitModelClasses, basePaths);

            if (openApiForProfile != null) {
                res.add(openApiForProfile);
            }
        }

        return res;
    }

    private OpenAPI createOpenAPIFromProfile(Map.Entry<String, List<CtType<?>>> profile, List<CtType<?>> explicitModelClasses, List<String> basePaths) {
        String currentProfileName = profile.getKey();
        var controllerClassesForCurrentProfile = profile.getValue();

        if (controllerClassesForCurrentProfile.isEmpty()) {
            System.out.println("Skipping empty profile: " + currentProfileName);
            return null;
        } else
            return createOpenAPIFromControllers(currentProfileName, controllerClassesForCurrentProfile, explicitModelClasses, basePaths);

    }

    private OpenAPI createOpenAPIFromControllers(String profileName, List<CtType<?>> controllerClasses, List<CtType<?>> explicitModelClasses, List<String> basePaths) {
        Paths paths = createPathsFromControllers(controllerClasses);

        // after all the paths are generated, we know about the referenced models
        // todo remove this global variable dependency to schemaHelper (filled by OperationTransformer)
        Set<CtTypeReference<?>> modelClasses = schemaHelper.referencedModelClasses;
        modelClasses.addAll(explicitModelClasses.stream().map(CtType::getReference).collect(Collectors.toUnmodifiableSet()));
        Components components = createComponentsSchemasFromModels(modelClasses);

        Info info = openApiGen.getDummyInfo(projectName, restFramework.getOpenApiInfoDescription(profileName));

        OpenAPI openApi = openApiGen.createOpenApi(info, paths, components);

        var servers = createServersFromBasePaths(basePaths);
        if (servers != null && !servers.isEmpty())
            openApi.setServers(servers);

        writeOpenApiToFile(openApi, profileName);

        return openApi;
    }

    protected void writeOpenApiToFile(OpenAPI openApi, String profileName) {
        var fileName = outputFileName.replace(".json", "") + "_" + profileName + ".json";
        openApiGen.writeOpenApiToFile(openApi, fileName);
        System.out.println("Wrote OpenAPI to " + fileName);
    }

    private List<Server> createServersFromBasePaths(List<String> basePaths) {
        List<Server> servers = new ArrayList<>();
        if (basePaths != null && !basePaths.isEmpty()) {
            for (String basePath : basePaths) {
                String normalizedPath = basePath.startsWith("/") ? basePath : "/" + basePath;
                normalizedPath = normalizedPath.length() > 1 && normalizedPath.endsWith("/")
                        ? normalizedPath.substring(0, normalizedPath.length()-1) : normalizedPath;

                Server server = new Server();
                server.setUrl(normalizedPath);
                servers.add(server);
            }
        }
        return servers;
    }

    // fixme split superclass and subresource analysis into dedicated steps
    //  e.g., for each (sub-)resource run the superclass hierarchy then continue
    private Paths createPathsFromControllers(List<CtType<?>> controllerClasses) {
        Paths operationsMap = new Paths();

        // contains the concreteType (most concrete implementation class) and currentType (iteratively towards super).
        List<ControllerClassProcessingInformation> notProcessedControllerClasses =
                controllerClasses.stream().map(t -> new ControllerClassProcessingInformation(t, t)).collect(Collectors.toList());

        while (notProcessedControllerClasses.size() > 0) {
            var curControllerClasses = new ArrayList<>(notProcessedControllerClasses);

            for (ControllerClassProcessingInformation currentControllerClassInfoUnderAnalysis : curControllerClasses) {
                CtType<?> concreteControllerClassType = currentControllerClassInfoUnderAnalysis.getConcreteControllerType();
                CtType<?> currentControllerClassInHierarchyType = currentControllerClassInfoUnderAnalysis.getCurrentSuperclassType();

                String controllerBasePath = currentControllerClassInfoUnderAnalysis.getBasePath() + '/' + operationsTransformer.getBaseControllerPath(concreteControllerClassType);
                DetectedApiParamsAndResponses previouslyDetectedApiParamsAndResponses = currentControllerClassInfoUnderAnalysis.getPreviouslyDetectedApiParamsAndResponses();

                /// REST API detection ///
                for (CtMethod<?> method : currentControllerClassInHierarchyType.getMethods()) {
                    Set<Operation> previousOperations = Collections.newSetFromMap(new IdentityHashMap<>());
                    operationsMap.values().forEach(item -> previousOperations.addAll(item.readOperations()));
                    // Adds the operation for the method to the operationsMap if it contains a corresponding annotation
                    operationsTransformer.createOperation(
                            method, controllerBasePath,
                            operationsMap, concreteControllerClassType.getSimpleName(),
                            previouslyDetectedApiParamsAndResponses);
                    operationsMap.forEach((path, item) -> item.readOperationsMap().forEach((httpMethod, operation) -> {
                        if (!previousOperations.contains(operation)) {
                            operationCreated(method, path, httpMethod);
                        }
                    }));
                }

                /// traversing down the controller's sub-resources ///
                List<ControllerClassProcessingInformation> subResources = findSubResourcesInController(currentControllerClassInfoUnderAnalysis, controllerBasePath);
                if (subResources != null) {
                    // break cycles
                    // todo report cycles as "see other endpoint"
                    for (ControllerClassProcessingInformation subResource : subResources) {
//                        System.out.println(subResource.getConcreteControllerType().getQualifiedName() + " -> " + subResource.getSubResourceChainAsString());
                        if (subResource.getParentResourceTypeChain().contains(subResource.getConcreteControllerType()))
                            System.out.println("Endpoint call chain circle detected, and ignored: " + subResource.getSubResourceChainAsString() + "-/->" + subResource.getConcreteControllerType().getSimpleName());
                        else
                            notProcessedControllerClasses.add(subResource);
                    }
                }

                // todo consider what to do for sub resource resolution (envirocar)
                /// traversing up the controller class's inheritance ///
                if (currentControllerClassInHierarchyType.getSuperclass() != null
                        && currentControllerClassInfoUnderAnalysis.getParentResourceTypeChain().size() < 2) // fixme temp solution for envirocar to stop generation
                    notProcessedControllerClasses.add(
                            new ControllerClassProcessingInformation(
                                    concreteControllerClassType,
                                    currentControllerClassInHierarchyType.getSuperclass().getTypeDeclaration(),
                                    currentControllerClassInfoUnderAnalysis.getParentResourceTypeChain(),
                                    currentControllerClassInfoUnderAnalysis.getBasePath(),
                                    currentControllerClassInfoUnderAnalysis.getPreviouslyDetectedApiParamsAndResponses())
                    );
            }

            notProcessedControllerClasses.removeAll(curControllerClasses);
        }

        operationsTransformer.fixDuplicateOperationIds(operationsMap);

        return operationsMap;
    }

    /** Receives actual transformed mappings, including competing source witnesses. */
    protected void operationCreated(CtMethod<?> method, String path, PathItem.HttpMethod httpMethod) {
    }

    private List<ControllerClassProcessingInformation> findSubResourcesInController(ControllerClassProcessingInformation controllerUnderAnalysis, String basePath) {
        List<ControllerClassProcessingInformation> detectedSubResources = new LinkedList<>();

        for (var subResource : restFramework.getSubResourcesInController(controllerUnderAnalysis.getCurrentSuperclassType())) {
            var fullPath = basePath + '/' + subResource.getPath();

            // identify parameters and responses that might be resolved at this level
            List<Parameter> parameters = operationsTransformer.transformParameters(fullPath, subResource.getServingMethod());
            ApiResponses responses = operationResponseCodeInterceptor.tryDetectExceptionsInMethod(subResource.getServingMethod());
            DetectedApiParamsAndResponses detectedApiParamsAndResponses = new DetectedApiParamsAndResponses(parameters, responses);

            detectedApiParamsAndResponses.addAllDetected(controllerUnderAnalysis.getPreviouslyDetectedApiParamsAndResponses());

            var updatedResourceResolutionChain = new ArrayList<>(controllerUnderAnalysis.getParentResourceTypeChain());
            updatedResourceResolutionChain.add(controllerUnderAnalysis.getConcreteControllerType());

            var subResourceProcessingInformation = new ControllerClassProcessingInformation(
                    subResource.getType(),
                    subResource.getType(),
                    updatedResourceResolutionChain,
                    fullPath,
                    detectedApiParamsAndResponses
            );

            detectedSubResources.add(subResourceProcessingInformation);
        }

        return detectedSubResources;
    }

    /**
     * Creates the schemas for all transitive referenced models starting with modelClasses.
     *
     * @param modelClasses
     * @return
     */
    private Components createComponentsSchemasFromModels(Set<CtTypeReference<?>> modelClasses) {
        // The modelClasses are extended when executing the schemaTransformer.
        // Hence, the schemaHelper used for the operationsTransformer must be used for the schemaTransformer.

        // we now use explicit model classes also
        // assert modelClasses == schemaHelper.referencedModelClasses;

        Map<String, Schema> schemaMap = new HashMap<>();
        Map<String, InheritanceInfo> inheritanceMap = new HashMap<>();

        Set<CtTypeReference<?>> processedClasses = new HashSet<>();
        while (modelClasses.size() > 0) {
            // process all model classes in the set
            for (CtTypeReference<?> modelClassRef : new ArrayList<>(modelClasses)) {
                Schema<?> transformedComponentSchema;
                CtType<?> modelClass = modelClassRef.getTypeDeclaration();
                if (modelClass != null && schemaHelper.isInPackagesToBeScanned(modelClass))
                    transformedComponentSchema = schemaTransformer.transformSimpleSchema(modelClass, inheritanceMap);
                else if (modelClassRef.getSimpleName().equals(DataTypeTransformer.UNSPECIFIED_SIMPLE_NAME)) {
                    // ignored on purpose during path generation
                    transformedComponentSchema = schemaTransformer.transformUnspecifiedSchema();
                } else {
                    // happens if the type is not defined inside the project, e.g., org.springframework.web.servlet.ModelAndView
                    transformedComponentSchema = schemaTransformer.transformExternalSchema(modelClassRef);
                }

                schemaMap.put(modelClassRef.getSimpleName(), transformedComponentSchema);
                processedClasses.add(modelClassRef);
            }
            // during processing the set is extended with newly encountered model classes in schemaHelper
            // remove the processed classes from it
            modelClasses.removeAll(processedClasses);
        }

        // restore the original + transitive set
        modelClasses.addAll(processedClasses);

        Components components = new Components();
        components.setSchemas(schemaMap);
        return components;
    }

    // TODO getControllerAnnotations, AdviceAnnotations, ModelSchemaAnnotations
    protected RelevantClasses getRelevantClassesFromPackages(Collection<CtPackage> packages) {
        List<CtType<?>> controllerClasses = new LinkedList<>();
        List<CtType<?>> globalExceptionHandlerClasses = new LinkedList<>();
        List<CtType<?>> applicationPathClasses = new LinkedList<>();
        List<CtType<?>> explicitModelClasses = new LinkedList<>();

        for (CtPackage pkg : packages) {
            for (CtType<?> type : pkg.getTypes()) {
                for (CtAnnotation<?> annotation : type.getAnnotations()) {
                    String annotationName = annotation.getAnnotationType().toString();
                    if (annotationName != null
                            && this.restFramework.getControllerAnnotations().contains(annotationName)
                            && isTypeInRestApiModule(type.getPosition())) {
                        controllerClasses.add(type);
                        break; // annotations
                    }

                    if (annotationName != null && this.restFramework.isGlobalExceptionHandler(annotationName, type)) {
                        globalExceptionHandlerClasses.add(type);
                        break; // annotations
                    }

                    if (annotationName != null && this.restFramework.isApplicationPath(annotationName, type)) {
                        applicationPathClasses.add(type);
                        break; // annotations
                    }

                    if (annotationName != null && this.restFramework.getModelSchemaAnnotations().contains(annotationName)) {
                        explicitModelClasses.add(type);
                        break; // annotations
                    }
                }
            }
        }

        return new RelevantClasses(controllerClasses, globalExceptionHandlerClasses, applicationPathClasses, explicitModelClasses);
    }

    // fixme performance intensive operation
    private boolean isTypeInRestApiModule(SourcePosition typePosition) {
        if (!typePosition.isValidPosition())
            return false;

        Path file = typePosition.getFile().toPath().toAbsolutePath().normalize();
        Path restApiModule = Path.of(restApiModulePath).toAbsolutePath().normalize();

        return file.startsWith(restApiModule);
    }

    private List<String> extractApplicationPathAnnotationValues(List<CtType<?>> applicationPathClasses) {
        List<String> paths = new ArrayList<>();

        for(CtType<?> type : applicationPathClasses) {
            for(CtAnnotation<?> annotation : type.getAnnotations()) {
                if(this.restFramework.isApplicationPath(annotation.getAnnotationType().toString(), type)) {
                    Object valueExpr = annotation.getValue("value");
                    if (valueExpr instanceof CtLiteral) {
                        Object value = ((CtLiteral<?>) valueExpr).getValue();
                        if (value != null) {
                            paths.add(value.toString());
                        }
                    }
                }
            }
        }
        return paths;
    }
}
