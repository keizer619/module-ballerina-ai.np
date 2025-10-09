/*
 *  Copyright (c) 2025, WSO2 Inc. (http://www.wso2.org).
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package io.ballerina.lib.ai.np.compilerplugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.ballerina.compiler.api.SemanticModel;
import io.ballerina.compiler.api.symbols.ConstantSymbol;
import io.ballerina.compiler.api.symbols.Symbol;
import io.ballerina.compiler.api.symbols.TypeSymbol;
import io.ballerina.compiler.syntax.tree.ExpressionNode;
import io.ballerina.compiler.syntax.tree.InterpolationNode;
import io.ballerina.compiler.syntax.tree.LiteralValueToken;
import io.ballerina.compiler.syntax.tree.ModulePartNode;
import io.ballerina.compiler.syntax.tree.NaturalExpressionNode;
import io.ballerina.compiler.syntax.tree.Node;
import io.ballerina.compiler.syntax.tree.NodeList;
import io.ballerina.compiler.syntax.tree.NodeParser;
import io.ballerina.projects.BuildOptions;
import io.ballerina.projects.DiagnosticResult;
import io.ballerina.projects.Document;
import io.ballerina.projects.DocumentId;
import io.ballerina.projects.Module;
import io.ballerina.projects.ModuleDescriptor;
import io.ballerina.projects.PackageCompilation;
import io.ballerina.projects.directory.BuildProject;
import io.ballerina.projects.util.ProjectUtils;
import io.ballerina.tools.diagnostics.Diagnostic;
import io.ballerina.tools.diagnostics.DiagnosticInfo;
import io.ballerina.tools.diagnostics.DiagnosticSeverity;
import io.ballerina.tools.text.LinePosition;
import io.ballerina.tools.text.LineRange;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

import static io.ballerina.lib.ai.np.compilerplugin.Commons.CONTENT;
import static io.ballerina.lib.ai.np.compilerplugin.Commons.FILE_PATH;

/**
 * Methods to generate code at compile-time.
 *
 * @since 0.4.0
 */
public class CodeGenerationUtils {

    private static final String TEMP_DIR_PREFIX = "ballerina-np-codegen-diagnostics-dir-";
    private static final String BALLERINA_TOML_FILE = "Ballerina.toml";
    private static final String TRIPLE_BACKTICK_BALLERINA = "```ballerina";
    private static final String TRIPLE_BACKTICK = "```";

    static String generateCodeForFunction(String copilotUrl, String copilotAccessToken, String originalFuncName,
                                          String generatedFuncName, String prompt, HttpClient client,
                                          JsonArray sourceFiles, ModuleDescriptor moduleDescriptor,
                                          String packageOrgName) {
        try {
            String generatedPrompt = generatePrompt(originalFuncName, generatedFuncName, prompt);
            GeneratedCode generatedCode = generateCode(copilotUrl, copilotAccessToken, client, sourceFiles,
                    generatedPrompt);

            updateSourceFilesWithGeneratedContent(sourceFiles, generatedFuncName, generatedCode);
            return repairCode(copilotUrl, copilotAccessToken, generatedFuncName, client, sourceFiles, moduleDescriptor,
                    generatedPrompt, generatedCode, packageOrgName);
        } catch (URISyntaxException e) {
            throw new RuntimeException("Failed to generate code, invalid URI for Copilot");
        } catch (ConnectException e) {
            throw new RuntimeException("Failed to connect to Copilot services");
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to generate code: " + e.getMessage());
        }
    }

    static ExpressionNode generateCodeForNaturalExpression(NaturalExpressionNode naturalExpressionNode,
                                                           String copilotUrl, String copilotAccessToken,
                                                           HttpClient client, JsonArray sourceFiles,
                                                           SemanticModel semanticModel,
                                                           TypeSymbol expectedType, Document document) {
        try {
            String generatedPrompt = generatePrompt(naturalExpressionNode, expectedType, semanticModel);
            GeneratedCode generatedCode = generateCode(copilotUrl, copilotAccessToken, client, sourceFiles,
                    generatedPrompt);
            ExpressionNode modifiedExpressionNode = NodeParser.parseExpression(generatedCode.code());
            JsonArray diagnostics =
                    collectConstNaturalExpressionDiagnostics(modifiedExpressionNode, generatedCode, document);
            if (diagnostics.isEmpty()) {
                return modifiedExpressionNode;
            }
            String repairedExpression = repairIfDiagnosticsExistForConstNaturalExpression(
                    copilotUrl, copilotAccessToken, client, sourceFiles,
                    generatedPrompt, generatedCode, diagnostics);
            return NodeParser.parseExpression(repairedExpression);
        } catch (URISyntaxException e) {
            throw new RuntimeException("Failed to generate code, invalid URI for Copilot");
        } catch (ConnectException e) {
            throw new RuntimeException("Failed to connect to Copilot services");
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to generate code: " + e.getMessage());
        }
    }

    private static GeneratedCode generateCode(String copilotUrl, String copilotAccessToken, HttpClient client,
                                              JsonArray sourceFiles, String generatedPrompt)
            throws URISyntaxException, IOException, InterruptedException {
        JsonObject codeGenerationPayload = constructCodeGenerationPayload(generatedPrompt, sourceFiles);
        HttpRequest codeGenerationRequest = HttpRequest.newBuilder()
                .uri(new URI(copilotUrl + "/code"))
                .header("Authorization", "Bearer " + copilotAccessToken)
                .POST(HttpRequest.BodyPublishers.ofString(codeGenerationPayload.toString())).build();
        Stream<String> lines = client.send(codeGenerationRequest, HttpResponse.BodyHandlers.ofLines()).body();
        return extractGeneratedFunctionCode(lines);
    }

    private static String repairCode(String copilotUrl, String copilotAccessToken, String generatedFuncName,
                                     HttpClient client, JsonArray sourceFiles, ModuleDescriptor moduleDescriptor,
                                     String generatedPrompt, GeneratedCode generatedCode, String packageOrgName)
            throws IOException, URISyntaxException, InterruptedException {
        String generatedFunctionSrc = repairIfDiagnosticsExist(copilotUrl, copilotAccessToken, client, sourceFiles,
                moduleDescriptor, generatedFuncName, generatedPrompt, generatedCode, packageOrgName);
        return repairIfDiagnosticsExist(copilotUrl, copilotAccessToken, client, sourceFiles, moduleDescriptor,
                generatedFuncName, generatedPrompt,
                new GeneratedCode(generatedFunctionSrc, generatedCode.functions), packageOrgName);
    }

    private static Optional<Document> findDocumentByName(Module module, String generateFuncName) {
        String documentName = getGeneratedBalFileName(generateFuncName);
        for (DocumentId docId : module.documentIds()) {
            Document doc = module.document(docId);
            if (doc.name().contains(documentName)) {
                return Optional.of(doc);
            }
        }
        return Optional.empty();
    }

    private static String repairIfDiagnosticsExist(String copilotUrl, String copilotAccessToken, HttpClient client,
                                                   JsonArray sourceFiles, ModuleDescriptor moduleDescriptor,
                                                   String generatedFuncName, String generatedPrompt,
                                                   GeneratedCode generatedCode, String packageOrgName)
            throws IOException, URISyntaxException, InterruptedException {
        ModulePartNode modulePartNode = NodeParser.parseModulePart(generatedCode.code);

        BuildProject project = createProject(sourceFiles, moduleDescriptor);
        Optional<JsonArray> compilerDiagnostics = getDiagnostics(project);
        Module module = project.currentPackage().module(
                project.currentPackage().modules().iterator().next().moduleId());
        JsonArray codeGeneratorDiagnostics = new AllowedConstructValidator(
                    project.currentPackage().getCompilation().getSemanticModel(module.moduleId()),
                    findDocumentByName(module, generatedFuncName), packageOrgName
                ).checkCodeGenerationDiagnostics(modulePartNode);
        JsonArray allDiagnostics = mergeDiagnostics(compilerDiagnostics,
                codeGeneratorDiagnostics);

        if (allDiagnostics.isEmpty()) {
            return generatedCode.code;
        }

        String repairResponse = repairCode(copilotUrl, copilotAccessToken, generatedFuncName, client, sourceFiles,
                generatedPrompt, generatedCode, allDiagnostics);

        return updateResourcesWithCodeSnippet(repairResponse, generatedCode, sourceFiles);
    }

    private static JsonArray collectConstNaturalExpressionDiagnostics(ExpressionNode expNode,
                                                                      GeneratedCode generatedCode, Document document) {
        JsonArray diagnostics = new JsonArray();
        Iterable<Diagnostic> compilerDiagnostics = expNode.diagnostics();

        compilerDiagnostics.forEach(diagnostic -> {
            JsonObject diagnosticObj = new JsonObject();
            diagnosticObj.addProperty("message", constructProjectDiagnosticMessage(expNode, 
                    diagnostic.message(), document));
            diagnostics.add(diagnosticObj);
        });

        JsonArray constantExpressionDiagnostics = new ConstantExpressionValidator(document)
                .checkNonConstExpressions(NodeParser.parseExpression(generatedCode.code));
        diagnostics.addAll(constantExpressionDiagnostics);
        return diagnostics;
    }

    private static String constructProjectDiagnosticMessage(Node node, String message, Document document) {
        LineRange lineRange = node.location().lineRange();
        LinePosition startLine = lineRange.startLine();
        LinePosition endLine = lineRange.endLine();
        return String.format("ERROR [%s:(%s:%s,%s:%s)] %s.",
                document.name(), startLine.line(), startLine.offset(), endLine.line(), endLine.offset(), message);
    }

    private static String repairIfDiagnosticsExistForConstNaturalExpression(String copilotUrl,
                                   String copilotAccessToken, HttpClient client, JsonArray sourceFiles,
                                   String generatedPrompt, GeneratedCode generatedCode, JsonArray diagnostics)
            throws IOException, URISyntaxException, InterruptedException {
        String repairResponse = repairCodeForConstNaturalExpressions(
                copilotUrl, copilotAccessToken, client, sourceFiles,
                generatedPrompt, generatedCode, diagnostics);

        return updateResourcesWithCodeSnippet(repairResponse, generatedCode, sourceFiles);
    }

    private static String updateResourcesWithCodeSnippet(String repairResponse, GeneratedCode generatedCode,
                                                         JsonArray sourceFiles) {
        if (hasBallerinaCodeSnippet(repairResponse)) {
            String generatedFunctionSrc = extractBallerinaCodeSnippet(repairResponse);
            sourceFiles.get(sourceFiles.size() - 1).getAsJsonObject().addProperty(CONTENT, generatedFunctionSrc);
            return generatedFunctionSrc;
        }
        return generatedCode.code;
    }

    private static JsonArray mergeDiagnostics(Optional<JsonArray> compilerDiagnostics,
                                              JsonArray validationDiagnostics) {
        JsonArray merged = new JsonArray();
        compilerDiagnostics.ifPresent(merged::addAll);
        merged.addAll(validationDiagnostics);
        return merged;
    }

    private static String repairCode(String copilotUrl, String copilotAccessToken, String generatedFuncName,
                                     HttpClient client, JsonArray updatedSourceFiles, String generatedPrompt,
                                     GeneratedCode generatedCode, JsonArray diagnostics)
            throws URISyntaxException, IOException, InterruptedException {
        JsonObject codeReparationPayload =
                constructCodeReparationPayload(generatedPrompt, generatedFuncName, generatedCode,
                        updatedSourceFiles, diagnostics);
        return getRepairResponse(copilotUrl, copilotAccessToken, client, codeReparationPayload);
    }

    private static String repairCodeForConstNaturalExpressions(String copilotUrl, String copilotAccessToken,
                                     HttpClient client, JsonArray updatedSourceFiles, String generatedPrompt,
                                     GeneratedCode generatedCode, JsonArray diagnostics)
            throws URISyntaxException, IOException, InterruptedException {
        JsonObject codeReparationPayload =
                constructCodeReparationPayloadForConstNaturalExpressions(generatedPrompt, generatedCode,
                        updatedSourceFiles, diagnostics);
        return getRepairResponse(copilotUrl, copilotAccessToken, client, codeReparationPayload);
    }

    private static String getRepairResponse(String copilotUrl, String copilotAccessToken, HttpClient client,
                                            JsonObject codeReparationPayload)
            throws URISyntaxException, IOException, InterruptedException {
        HttpRequest codeReparationRequest = HttpRequest.newBuilder()
                .uri(new URI(copilotUrl + "/code/repair"))
                .header("Authorization", "Bearer " + copilotAccessToken)
                .POST(HttpRequest.BodyPublishers.ofString(codeReparationPayload.toString())).build();
        String body = client.send(codeReparationRequest, HttpResponse.BodyHandlers.ofString()).body();
        return JsonParser.parseString(body).getAsJsonObject()
                .getAsJsonPrimitive("repairResponse").getAsString();
    }

    private static Optional<JsonArray> getDiagnostics(BuildProject project) {
        JsonObject diagnosticObj;
        PackageCompilation compilation = project.currentPackage().getCompilation();
        DiagnosticResult diagnosticResult = compilation.diagnosticResult();

        if (diagnosticResult.errorCount() == 0) {
            return Optional.empty();
        }

        JsonArray diagnostics = new JsonArray();
        for (Diagnostic diagnostic : diagnosticResult.diagnostics()) {
            DiagnosticInfo diagnosticInfo = diagnostic.diagnosticInfo();
            if (diagnosticInfo.severity() != DiagnosticSeverity.ERROR) {
                continue;
            }

            diagnosticObj = new JsonObject();
            diagnosticObj.addProperty("message", diagnostic.toString());
            diagnostics.add(diagnosticObj);
        }

        return Optional.of(diagnostics);
    }

    private static BuildProject createProject(JsonArray sourceFiles, ModuleDescriptor moduleDescriptor)
            throws IOException {
        Path tempProjectDir = Files.createTempDirectory(TEMP_DIR_PREFIX + System.currentTimeMillis());
        tempProjectDir.toFile().deleteOnExit();

        Path tempGeneratedDir = Files.createDirectory(tempProjectDir.resolve("generated"));
        tempGeneratedDir.toFile().deleteOnExit();

        for (JsonElement sourceFile : sourceFiles) {
            JsonObject sourceFileObj = sourceFile.getAsJsonObject();
            File file = Files.createFile(
                    tempProjectDir.resolve(Path.of(sourceFileObj.get(FILE_PATH).getAsString()))).toFile();
            file.deleteOnExit();

            try (FileWriter fileWriter = new FileWriter(file, StandardCharsets.UTF_8)) {
                fileWriter.write(sourceFileObj.get(CONTENT).getAsString());
            }
        }

        Path ballerinaTomlPath = tempProjectDir.resolve(BALLERINA_TOML_FILE);
        File balTomlFile = Files.createFile(ballerinaTomlPath).toFile();
        balTomlFile.deleteOnExit();

        try (FileWriter fileWriter = new FileWriter(balTomlFile, StandardCharsets.UTF_8)) {
            fileWriter.write(String.format("""
                [package]
                org = "%s"
                name = "%s"
                version = "%s"
                """,
                    moduleDescriptor.org().value(),
                    moduleDescriptor.packageName().value(),
                    moduleDescriptor.version().value()));
        }

        BuildOptions buildOptions = BuildOptions.builder()
                .setExperimental(true)
                .targetDir(ProjectUtils.getTemporaryTargetPath())
                .build();
        return BuildProject.load(tempProjectDir, buildOptions);
    }

    private static GeneratedCode extractGeneratedFunctionCode(Stream<String> lines) {
        String[] linesArr = lines.toArray(String[]::new);
        int length = linesArr.length;

        if (length == 1) {
            JsonObject jsonObject = JsonParser.parseString(linesArr[0]).getAsJsonObject();
            if (jsonObject.has("error_message")) {
                throw new RuntimeException(jsonObject.get("error_message").getAsString());
            }
        }

        StringBuilder responseBody = new StringBuilder();
        JsonArray functions = null;

        int index = 0;
        while (index < length) {
            String line = linesArr[index];

            if (line.isBlank()) {
                index++;
                continue;
            }

            if ("event: content_block_delta".equals(line)) {
                line = linesArr[++index].substring(6);
                responseBody.append(JsonParser.parseString(line).getAsJsonObject()
                        .getAsJsonPrimitive("text").getAsString());
                continue;
            }

            if ("event: functions".equals(line)) {
                line = linesArr[++index].substring(6);
                functions = JsonParser.parseString(line).getAsJsonArray();
                continue;
            }

            index++;
        }

        String responseBodyString = responseBody.toString();
        return new GeneratedCode(extractBallerinaCodeSnippet(responseBodyString), functions);
    }

    private static boolean hasBallerinaCodeSnippet(String responseBodyString) {
        return responseBodyString.contains(TRIPLE_BACKTICK) && responseBodyString.contains(TRIPLE_BACKTICK);
    }

    private static String extractBallerinaCodeSnippet(String responseBodyString) {
        int startDelimLength = 12;
        int startIndex = responseBodyString.indexOf(TRIPLE_BACKTICK_BALLERINA);

        if (startIndex == -1) {
            startIndex = responseBodyString.indexOf(TRIPLE_BACKTICK);
            startDelimLength = 3;
        }

        int endIndex = responseBodyString.lastIndexOf(TRIPLE_BACKTICK);
        return (startIndex == -1 || endIndex == -1) ?
                responseBodyString :
                responseBodyString.substring(startIndex + startDelimLength, endIndex).trim();
    }

    private record GeneratedCode(String code, JsonArray functions) { }

    private static JsonObject constructCodeGenerationPayload(String prompt, JsonArray sourceFiles) {
        JsonObject payload = new JsonObject();
        payload.addProperty("usecase", prompt);
        payload.add("sourceFiles", sourceFiles);
        return payload;
    }

    private static String generatePrompt(String originalFuncName, String generatedFuncName, String prompt) {
        return String.format("""
                        An `external` function with the `@natural:code` Ballerina annotation needs to be replaced at
                        compile-time with the code necessary to achieve the requirement specified as the `prompt`
                        field in the annotation.
                        
                        As a skilled Ballerina programmer, you have to generate the code to do this for the %s function.
                        The following prompt defines the requirement:
                        
                        ```
                        %s
                        ```
                        
                        Your task is to generate a function named '%s' with the code that is needed to satisfy this user
                        prompt.
                        
                        The '%s' function should have exactly the same signature as the '%s' function.
                        Use only the parameters passed to the function and module-level clients that are clients \
                        from the ballerina and ballerinax module in the generated code.
                        Do not use any configurable variables or module-level variables defined in the program.
                        
                        The generated source code will be added in a separate file. Therefore, even if a module
                        required is already imported in the file with the original function, you MUST include any
                        import declarations required by the generated functions as import declarations in the code
                        snippet.
                        
                        Respond with ONLY THE GENERATED FUNCTION AND ANY IMPORTS REQUIRED BY THE GENERATED FUNCTION.
                        """,
                originalFuncName, prompt, generatedFuncName, generatedFuncName, originalFuncName);
    }

    private static String generatePrompt(NaturalExpressionNode naturalExpressionNode,
                                         TypeSymbol expectedType, SemanticModel semanticModel) {
        NodeList<Node> userPromptContent = naturalExpressionNode.prompt();
        StringBuilder sb = new StringBuilder(String.format("""
                Generate a value expression to satisfy the following requirement using only Ballerina literals and
                constructor expressions. The expression should be self-contained and should not have references.
                
                Ballerina literals:
                1. nil-literal :=  () | null
                2. boolean-literal := true | false
                3. numeric-literal - int, float, and decimal values (e.g., 1, 2.0, 3f, 4.5d)
                4. string-literal - double quoted strings (e.g., "foo") or
                    string-template literal without interpolations (e.g., string `foo`)
                
                Ballerina constructor expressions:
                1. List constructor expression - e.g., [1, 2]
                2. Mapping constructor expression - e.g., {a: 1, b: 2, "c": 3}
                3. Table constructor expression - e.g., table [{a: 1, b: 2}, {a: 2, b: 4}]
                
                The value should belong to the type '%s'. This value will be used in the code in place of the
                `const natural {...}` expression with the requirement.

                Respond with ONLY THE VALUE EXPRESSION within ```ballerina and ```.
                
                Requirement:
                """, expectedType.signature()));

        for (int i = 0; i < userPromptContent.size(); i++) {
            Node node = userPromptContent.get(i);
            if (node instanceof LiteralValueToken literalValueToken) {
                sb.append(literalValueToken.text());
                continue;
            }

            Symbol symbol = semanticModel.symbol(((InterpolationNode) node).expression()).get();
            if (symbol instanceof ConstantSymbol constantSymbol) {
                sb.append(constantSymbol.resolvedValue().get());
            }
        }
        return sb.toString();
    }

    private static void updateSourceFilesWithGeneratedContent(JsonArray sourceFiles, String generatedFuncName,
                                                              GeneratedCode generatedCode) {
        JsonObject sourceFile = new JsonObject();
        sourceFile.addProperty(FILE_PATH, String.format("generated/functions_%s.bal", generatedFuncName));
        sourceFile.addProperty(CONTENT, generatedCode.code);
        sourceFiles.add(sourceFile);
    }

    private static String getGeneratedBalFileName(String generatedFuncName) {
        return String.format("functions_%s.bal", generatedFuncName);
    }

    private static JsonObject constructCodeReparationPayload(String generatedPrompt, String generatedFuncName,
                                                             GeneratedCode generatedCode, JsonArray sourceFiles,
                                                             JsonArray diagnostics) {
        JsonObject payload = new JsonObject();

        payload.addProperty(
                "usecase", String.format("Fix issues in the generated '%s' function. " +
                        "Do not change anything other than the function body", generatedFuncName));
        return updateResourcePayload(payload, generatedPrompt, generatedCode, diagnostics, sourceFiles);
    }

    private static JsonObject constructCodeReparationPayloadForConstNaturalExpressions(
            String generatedPrompt, GeneratedCode generatedCode, JsonArray sourceFiles,
            JsonArray diagnostics) {
        JsonObject payload = new JsonObject();
        payload.addProperty("usecase",
              "The generated expression results in the following errors. " +
                    "Fix the errors and return a new constant expression.");

        return updateResourcePayload(payload, generatedPrompt, generatedCode, diagnostics, sourceFiles);
    }

    private static JsonObject updateResourcePayload(JsonObject payload, String generatedPrompt,
                        GeneratedCode generatedCode, JsonArray diagnostics, JsonArray sourceFiles) {
        payload.add("sourceFiles", sourceFiles);

        JsonObject chatHistoryMember = new JsonObject();
        chatHistoryMember.addProperty("actor", "user");
        chatHistoryMember.addProperty("message", generatedPrompt);
        JsonArray chatHistory = new JsonArray();
        chatHistory.add(chatHistoryMember);
        payload.add("chatHistory", chatHistory);
        payload.add("functions", generatedCode.functions);

        JsonObject diagnosticRequest = new JsonObject();
        diagnosticRequest.add("diagnostics", diagnostics);
        diagnosticRequest.addProperty("response", generatedCode.code);
        payload.add("diagnosticRequest", diagnosticRequest);

        return payload;
    }
}
