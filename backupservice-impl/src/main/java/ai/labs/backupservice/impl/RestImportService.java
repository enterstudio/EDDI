package ai.labs.backupservice.impl;

import ai.labs.backupservice.IRestImportService;
import ai.labs.backupservice.IZipArchive;
import ai.labs.resources.rest.behavior.IRestBehaviorStore;
import ai.labs.resources.rest.behavior.model.BehaviorConfiguration;
import ai.labs.resources.rest.bots.IRestBotStore;
import ai.labs.resources.rest.bots.model.BotConfiguration;
import ai.labs.resources.rest.documentdescriptor.IRestDocumentDescriptorStore;
import ai.labs.resources.rest.documentdescriptor.model.DocumentDescriptor;
import ai.labs.resources.rest.output.IRestOutputStore;
import ai.labs.resources.rest.output.model.OutputConfigurationSet;
import ai.labs.resources.rest.packages.IRestPackageStore;
import ai.labs.resources.rest.packages.model.PackageConfiguration;
import ai.labs.resources.rest.patch.PatchInstruction;
import ai.labs.resources.rest.regulardictionary.IRestRegularDictionaryStore;
import ai.labs.resources.rest.regulardictionary.model.RegularDictionaryConfiguration;
import ai.labs.rest.restinterfaces.IRestInterfaceFactory;
import ai.labs.rest.restinterfaces.RestInterfaceFactory;
import ai.labs.serialization.IJsonSerialization;
import ai.labs.utilities.FileUtilities;
import ai.labs.utilities.RestUtilities;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Named;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.core.Response;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static ai.labs.persistence.IResourceStore.IResourceId;

/**
 * @author ginccc
 */
@Slf4j
public class RestImportService extends AbstractBackupService implements IRestImportService {
    private static final Pattern EDDI_URI_PATTERN = Pattern.compile("\"eddi://ai.labs..*?\"");
    private static final String BOT_FILE_ENDING = ".bot.json";
    private final Path tmpPath = Paths.get(FileUtilities.buildPath(System.getProperty("user.dir"), "tmp", "import"));
    private final IZipArchive zipArchive;
    private final IJsonSerialization jsonSerialization;
    private final IRestInterfaceFactory restInterfaceFactory;
    private final String apiServerURI;

    @Inject
    public RestImportService(IZipArchive zipArchive,
                             IJsonSerialization jsonSerialization,
                             IRestInterfaceFactory restInterfaceFactory,
                             @Named("system.apiServerURI") String apiServerURI) {
        this.zipArchive = zipArchive;
        this.jsonSerialization = jsonSerialization;
        this.restInterfaceFactory = restInterfaceFactory;
        this.apiServerURI = apiServerURI;
    }

    @Override
    public void importBot(InputStream zippedBotConfigFiles, AsyncResponse response) {
        try {
            response.setTimeout(60, TimeUnit.SECONDS);
            File targetDir = new File(FileUtilities.buildPath(tmpPath.toString(), UUID.randomUUID().toString()));
            this.zipArchive.unzip(zippedBotConfigFiles, targetDir);

            String targetDirPath = targetDir.getPath();
            Files.newDirectoryStream(Paths.get(targetDirPath),
                    path -> path.toString().endsWith(BOT_FILE_ENDING))
                    .forEach(botFilePath -> {
                        try {
                            String botFileString = readFile(botFilePath);
                            BotConfiguration botConfiguration =
                                    jsonSerialization.deserialize(botFileString, BotConfiguration.class);
                            botConfiguration.getPackages().forEach(packageUri ->
                                    parsePackage(targetDirPath, packageUri, botConfiguration, response));

                            URI newBotUri = createNewBot(botConfiguration);
                            updateDocumentDescriptor(Paths.get(targetDirPath), buildOldBotUri(botFilePath), newBotUri);
                            response.resume(Response.ok().location(newBotUri).build());
                        } catch (IOException | RestInterfaceFactory.RestInterfaceFactoryException e) {
                            log.error(e.getLocalizedMessage(), e);
                            response.resume(new InternalServerErrorException());
                        }
                    });
        } catch (IOException e) {
            log.error(e.getLocalizedMessage(), e);
            response.resume(new InternalServerErrorException());
        }
    }

    private URI buildOldBotUri(Path botPath) {
        String botPathString = botPath.toString();
        String oldBotId = botPathString.substring(botPathString.lastIndexOf(File.separator) + 1,
                botPathString.lastIndexOf(BOT_FILE_ENDING));

        return URI.create(IRestBotStore.resourceURI + oldBotId + IRestBotStore.versionQueryParam + "1");
    }

    private void parsePackage(String targetDirPath, URI packageUri, BotConfiguration botConfiguration, AsyncResponse response) {
        try {
            IResourceId packageResourceId = RestUtilities.extractResourceId(packageUri);
            String packageId = packageResourceId.getId();
            String packageVersion = String.valueOf(packageResourceId.getVersion());

            Files.newDirectoryStream(Paths.get(FileUtilities.buildPath(targetDirPath, packageId, packageVersion)),
                    packageFilePath -> packageFilePath.toString().endsWith(".package.json")).
                    forEach(packageFilePath -> {
                        try {
                            Path packagePath = packageFilePath.getParent();
                            String packageFileString = readFile(packageFilePath);

                            // loading old resources, creating them in the new system,
                            // updating document descriptor and replacing references in package config

                            // ... for dictionaries
                            List<URI> dictionaryUris = extractResourcesUris(packageFileString, DICTIONARY_URI_PATTERN);
                            List<URI> newDictionaryUris = createNewDictionaries(
                                    readResources(dictionaryUris, packagePath,
                                            DICTIONARY_EXT, RegularDictionaryConfiguration.class));

                            updateDocumentDescriptor(packagePath, dictionaryUris, newDictionaryUris);
                            packageFileString = replaceURIs(packageFileString, dictionaryUris, newDictionaryUris);

                            // ... for behavior
                            List<URI> behaviorUris = extractResourcesUris(packageFileString, BEHAVIOR_URI_PATTERN);
                            List<URI> newBehaviorUris = createNewBehaviors(
                                    readResources(behaviorUris, packagePath,
                                            BEHAVIOR_EXT, BehaviorConfiguration.class));

                            updateDocumentDescriptor(packagePath, behaviorUris, newBehaviorUris);
                            packageFileString = replaceURIs(packageFileString, behaviorUris, newBehaviorUris);

                            // ... for output
                            List<URI> outputUris = extractResourcesUris(packageFileString, OUTPUT_URI_PATTERN);
                            List<URI> newOutputUris = createNewOutputs(
                                    readResources(outputUris, packagePath,
                                            OUTPUT_EXT, OutputConfigurationSet.class));

                            updateDocumentDescriptor(packagePath, outputUris, newOutputUris);
                            packageFileString = replaceURIs(packageFileString, outputUris, newOutputUris);

                            // creating updated package and replacing references in bot config
                            URI newPackageUri = createNewPackage(packageFileString);
                            updateDocumentDescriptor(packagePath, packageUri, newPackageUri);
                            botConfiguration.setPackages(botConfiguration.getPackages().stream().
                                    map(uri -> uri.equals(packageUri) ? newPackageUri : uri).
                                    collect(Collectors.toList()));

                        } catch (IOException | RestInterfaceFactory.RestInterfaceFactoryException |
                                CallbackMatcher.CallbackMatcherException e) {
                            log.error(e.getLocalizedMessage(), e);
                            response.resume(new InternalServerErrorException());
                        }
                    });
        } catch (IOException e) {
            log.error(e.getLocalizedMessage(), e);
            response.resume(new InternalServerErrorException());
        }
    }

    private URI createNewBot(BotConfiguration botConfiguration)
            throws RestInterfaceFactory.RestInterfaceFactoryException {
        IRestBotStore restPackageStore = getRestResourceStore(IRestBotStore.class);
        Response botResponse = restPackageStore.createBot(botConfiguration);
        return botResponse.getLocation();
    }

    private URI createNewPackage(String packageFileString)
            throws RestInterfaceFactory.RestInterfaceFactoryException, IOException {
        PackageConfiguration packageConfiguration =
                jsonSerialization.deserialize(packageFileString, PackageConfiguration.class);
        IRestPackageStore restPackageStore = getRestResourceStore(IRestPackageStore.class);
        Response packageResponse = restPackageStore.createPackage(packageConfiguration);
        return packageResponse.getLocation();
    }

    private List<URI> createNewDictionaries(List<RegularDictionaryConfiguration> dictionaryConfigurations)
            throws RestInterfaceFactory.RestInterfaceFactoryException {
        IRestRegularDictionaryStore restDictionaryStore = getRestResourceStore(IRestRegularDictionaryStore.class);
        return dictionaryConfigurations.stream().map(regularDictionaryConfiguration -> {
            Response dictionaryResponse = restDictionaryStore.createRegularDictionary(regularDictionaryConfiguration);
            return dictionaryResponse.getLocation();
        }).collect(Collectors.toList());
    }

    private List<URI> createNewBehaviors(List<BehaviorConfiguration> behaviorConfigurations)
            throws RestInterfaceFactory.RestInterfaceFactoryException {
        IRestBehaviorStore restBehaviorStore = getRestResourceStore(IRestBehaviorStore.class);
        return behaviorConfigurations.stream().map(behaviorConfiguration -> {
            Response behaviorResponse = restBehaviorStore.createBehaviorRuleSet(behaviorConfiguration);
            return behaviorResponse.getLocation();
        }).collect(Collectors.toList());
    }

    private List<URI> createNewOutputs(List<OutputConfigurationSet> outputConfigurations)
            throws RestInterfaceFactory.RestInterfaceFactoryException {
        IRestOutputStore restOutputStore = getRestResourceStore(IRestOutputStore.class);
        return outputConfigurations.stream().map(outputConfiguration -> {
            Response dictionaryResponse = restOutputStore.createOutputSet(outputConfiguration);
            return dictionaryResponse.getLocation();
        }).collect(Collectors.toList());
    }

    private void updateDocumentDescriptor(Path directoryPath, URI oldUri, URI newUri)
            throws RestInterfaceFactory.RestInterfaceFactoryException {
        updateDocumentDescriptor(directoryPath, Collections.singletonList(oldUri), Collections.singletonList(newUri));
    }

    private void updateDocumentDescriptor(Path directoryPath, List<URI> oldUris, List<URI> newUris)
            throws RestInterfaceFactory.RestInterfaceFactoryException {

        IRestDocumentDescriptorStore restDocumentDescriptorStore = getRestResourceStore(IRestDocumentDescriptorStore.class);
        IntStream.range(0, oldUris.size()).forEach(idx -> {
            try {
                URI oldUri = oldUris.get(idx);
                IResourceId oldResourceId = RestUtilities.extractResourceId(oldUri);
                DocumentDescriptor oldDocumentDescriptor = readDocumentDescriptorFromFile(directoryPath, oldResourceId);

                URI newUri = newUris.get(idx);
                IResourceId newResourceId = RestUtilities.extractResourceId(newUri);

                PatchInstruction<DocumentDescriptor> patchInstruction = new PatchInstruction<>();
                patchInstruction.setOperation(PatchInstruction.PatchOperation.SET);
                patchInstruction.setDocument(oldDocumentDescriptor);

                restDocumentDescriptorStore.patchDescriptor(newResourceId.getId(), newResourceId.getVersion(), patchInstruction);
            } catch (IOException e) {
                log.error(e.getLocalizedMessage(), e);
            }
        });
    }

    private DocumentDescriptor readDocumentDescriptorFromFile(Path packagePath, IResourceId resourceId)
            throws IOException {
        Path filePath = Paths.get(FileUtilities.buildPath(packagePath.toString(), resourceId.getId() + ".descriptor.json"));
        String oldDocumentDescriptorFile = readFile(filePath);
        return jsonSerialization.deserialize(oldDocumentDescriptorFile, DocumentDescriptor.class);
    }

    private String replaceURIs(String resourceString, List<URI> oldUris, List<URI> newUris)
            throws CallbackMatcher.CallbackMatcherException {
        Map<String, String> uriMap = toMap(oldUris, newUris);
        CallbackMatcher callbackMatcher = new CallbackMatcher(EDDI_URI_PATTERN);
        return callbackMatcher.replaceMatches(resourceString, matchResult -> {
            String match = matchResult.group();
            String key = match.substring(1, match.length() - 1);
            return uriMap.containsKey(key) ? "\"" + uriMap.get(key) + "\"" : null;
        });
    }

    private Map<String, String> toMap(List<URI> oldUris, List<URI> newUris) {
        Map<String, String> ret = new LinkedHashMap<>();
        for (int i = 0; i < oldUris.size(); i++) {
            ret.put(oldUris.get(i).toString(), newUris.get(i).toString());
        }
        return ret;
    }

    private <T> T getRestResourceStore(Class<T> clazz) throws RestInterfaceFactory.RestInterfaceFactoryException {
        return restInterfaceFactory.get(clazz, apiServerURI);
    }

    private <T> List<T> readResources(List<URI> uris, Path packagePath, String extension, Class<T> clazz) {
        return uris.stream().map(uri -> {
            try {
                IResourceId resourceId = RestUtilities.extractResourceId(uri);
                Path resourcePath = createResourcePath(packagePath, resourceId.getId(), extension);
                String resourceContent = readFile(resourcePath);
                return jsonSerialization.deserialize(resourceContent, clazz);
            } catch (IOException e) {
                e.printStackTrace();
                return null;
            }
        }).collect(Collectors.toList());
    }

    private Path createResourcePath(Path packagePath, String resourceId, String extension) {
        return Paths.get(FileUtilities.buildPath(packagePath.toString(), resourceId + "." + extension + ".json"));
    }

    private String readFile(Path path) throws FileNotFoundException {
        StringBuilder ret = new StringBuilder();
        try (Scanner scanner = new Scanner(new File(path.toString()))) {
            while (scanner.hasNext()) {
                ret.append(scanner.nextLine());
            }
        }

        return ret.toString();
    }
}
