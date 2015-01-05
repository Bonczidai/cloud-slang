/*******************************************************************************
* (c) Copyright 2014 Hewlett-Packard Development Company, L.P.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License v2.0 which accompany this distribution.
*
* The Apache License is available at
* http://www.apache.org/licenses/LICENSE-2.0
*
*******************************************************************************/

package org.openscore.lang.compiler;

import ch.lambdaj.Lambda;
import ch.lambdaj.function.convert.Converter;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.Validate;
import org.openscore.api.ExecutionPlan;
import org.openscore.lang.compiler.model.Executable;
import org.openscore.lang.compiler.model.Flow;
import org.openscore.lang.compiler.model.Operation;
import org.openscore.lang.compiler.model.ParsedSlang;
import org.openscore.lang.compiler.model.SlangPreCompiledMetaData;
import org.openscore.lang.compiler.utils.DependenciesHelper;
import org.openscore.lang.compiler.utils.ExecutableBuilder;
import org.openscore.lang.compiler.utils.ExecutionPlanBuilder;
import org.openscore.lang.compiler.utils.YamlParser;
import org.openscore.lang.entities.CompilationArtifact;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ch.lambdaj.Lambda.convertMap;
import static ch.lambdaj.Lambda.having;
import static ch.lambdaj.Lambda.on;
import static org.hamcrest.Matchers.equalTo;

/*
 * Created by orius123 on 05/11/14.
 */
@Component
public class SlangCompilerImpl implements SlangCompiler {

    @Autowired
    private ExecutableBuilder executableBuilder;

    @Autowired
    private DependenciesHelper dependenciesHelper;

    @Autowired
    private ExecutionPlanBuilder executionPlanBuilder;

    @Autowired
    private YamlParser yamlParser;

    @Override
    public CompilationArtifact compileFlow(SlangSource source, Set<SlangSource> path) {
        return compile(source, null, path);
    }

    @Override
    public CompilationArtifact compile(SlangSource source, String operationName, Set<SlangSource> path) {

        Validate.notNull(source, "You must supply a source to compile");

        //first thing we parse the yaml file into java maps
        ParsedSlang parsedSlang = yamlParser.parse(source);

        //than we transform those maps to model objects
        Executable executable = transformToExecutable(operationName, parsedSlang);

        Map<String, Executable> filteredDependencies = new HashMap<>();
        //we handle dependencies only if the file has imports
        boolean hasDependencies = MapUtils.isNotEmpty(parsedSlang.getImports())
                && executable.getType().equals(SlangTextualKeys.FLOW_TYPE);
        if (hasDependencies) {
            Validate.noNullElements(path, "Source that was requested to compile has imports but no path was given");

            //we transform also all of the files in the given path to model objects
            Map<String, Executable> pathExecutables = transformDependencies(path);

            //we add the current executable since a dependency can require it
            List<Executable> availableExecutables = new ArrayList<>(pathExecutables.values());
            availableExecutables.add(executable);

            //than we match the references to the actual dependencies
            filteredDependencies = dependenciesHelper.matchReferences(executable, availableExecutables);
        }

        //next we create an execution plan for the required executable
        ExecutionPlan executionPlan = compileToExecutionPlan(executable);

        //and also create execution plans for all other dependencies
        Map<String, ExecutionPlan> dependencies = convertMap(filteredDependencies, new Converter<Executable, ExecutionPlan>() {
            @Override
            public ExecutionPlan convert(Executable compiledExecutable) {
                return compileToExecutionPlan(compiledExecutable);
            }
        });

        return new CompilationArtifact(executionPlan, dependencies, executable.getInputs());
    }

    /**
     * Transforms all of the slang files in the given path to {@link org.openscore.lang.compiler.model.Executable}
     *
     * @param path the path
     * @return a map of {@link org.openscore.lang.compiler.model.Executable} with their ids as key
     */
    private Map<String, Executable> transformDependencies(Set<SlangSource> path) {

        //we transform and add all of the dependencies to a list of executable
        List<Executable> executables = new ArrayList<>();
        for (SlangSource source : path) {
            ParsedSlang parsedSlang = yamlParser.parse(source);
            switch (parsedSlang.getType()) {
                case FLOW:
                    executables.add(transformFlow(parsedSlang));
                    break;
                case OPERATIONS:
                    executables.addAll(transformOperations(parsedSlang));
                    break;
                default:
                    throw new RuntimeException("Source: " + source.getName() + " is not of flow type or operations");
            }
        }

        //we put the dependencies in a map with their id as key
        Map<String, Executable> compiledExecutableMap = new HashMap<>();
        for (Executable executable : executables) {
            compiledExecutableMap.put(executable.getId(), executable);
        }
        return compiledExecutableMap;
    }

    /**
     * Utility method that cast a {@link org.openscore.lang.compiler.model.Executable} to its subtype
     * and create an {@link org.openscore.api.ExecutionPlan} for it
     *
     * @param executable the executable to create an {@link org.openscore.api.ExecutionPlan} for
     * @return {@link org.openscore.api.ExecutionPlan} of the given {@link org.openscore.lang.compiler.model.Executable}
     */
    private ExecutionPlan compileToExecutionPlan(Executable executable) {
        ExecutionPlan executionPlan;

        if (executable.getType().equals(SlangTextualKeys.OPERATION_TYPE)) {
            executionPlan = executionPlanBuilder.createOperationExecutionPlan((Operation) executable);
        } else if (executable.getType().equals(SlangTextualKeys.FLOW_TYPE)) {
            executionPlan = executionPlanBuilder.createFlowExecutionPlan((Flow) executable);
        } else {
            throw new RuntimeException("Executable: " + executable.getName() + " is not a flow and not an operation");
        }
        return executionPlan;
    }

    /**
     * Utility method that transform a {@link org.openscore.lang.compiler.model.ParsedSlang}
     * into a {@link org.openscore.lang.compiler.model.Executable}
     * also handles operations files
     *
     * @param operationName the name of the operation to transform from the {@link org.openscore.lang.compiler.model.ParsedSlang}
     * @param parsedSlang the source to transform
     * @return {@link org.openscore.lang.compiler.model.Executable}  of the requested flow/operation
     */
    private Executable transformToExecutable(String operationName, ParsedSlang parsedSlang) {
        Executable executable;

        switch (parsedSlang.getType()) {
            case OPERATIONS:
                Validate.notEmpty(operationName, "Source: " + parsedSlang.getName() + " is operations source " +
                        "you must specify the operation name requested for compiling");
                List<Executable> compilesOperations = transformOperations(parsedSlang);
                // match the requested operation from all the operations in the source
                executable = Lambda.selectFirst(compilesOperations, having(on(Executable.class).getName(), equalTo(operationName)));
                if (executable == null) {
                    throw new RuntimeException("Operation with name: " + operationName + " wasn't found in source: " + parsedSlang.getName());
                }
                break;
            case FLOW:
                executable = transformFlow(parsedSlang);
                break;
            default:
                throw new RuntimeException("source: " + parsedSlang.getName() + " is not of flow type or operations");
        }
        return executable;
    }

    /**
     * transform an operations {@link org.openscore.lang.compiler.model.ParsedSlang} to a List of {@link org.openscore.lang.compiler.model.Executable}
     *
     * @param parsedSlang the source to transform the operations from
     * @return List of {@link org.openscore.lang.compiler.model.Executable} representing the operations in the source
     */
    private List<Executable> transformOperations(ParsedSlang parsedSlang) {
        List<Executable> executables = new ArrayList<>();
        for (Map<String, Map<String, Object>> operation : parsedSlang.getOperations()) {
            Map.Entry<String, Map<String, Object>> entry = operation.entrySet().iterator().next();
            String operationName = entry.getKey();
            Map<String, Object> operationRawData = entry.getValue();
            executables.add(executableBuilder.transformToExecutable(parsedSlang, operationName, operationRawData));
        }
        return executables;
    }

    /**
     * transform an flow {@link org.openscore.lang.compiler.model.ParsedSlang} to a {@link org.openscore.lang.compiler.model.Executable}
     *
     * @param parsedSlang the source to transform the flow from
     * @return {@link org.openscore.lang.compiler.model.Executable} representing the flow in the source
     */
    private Executable transformFlow(ParsedSlang parsedSlang) {
        Map<String, Object> flowRawData = parsedSlang.getFlow();
        String flowName = (String) flowRawData.get(SlangTextualKeys.FLOW_NAME_KEY);
        if (StringUtils.isBlank(flowName)) {
            throw new RuntimeException("Flow in source: " + parsedSlang.getName() + "have no name");
        }
        return executableBuilder.transformToExecutable(parsedSlang, flowName, flowRawData);
    }

    @Override
    public SlangPreCompiledMetaData preCompileFlow(SlangSource source) {
        return preCompile(null, source);
    }

    @Override
    public SlangPreCompiledMetaData preCompile(String operationName, SlangSource source) {

        Validate.notNull(source, "You must supply a source to compile");

        //first thing we parse the yaml file into java maps
        ParsedSlang parsedSlang = yamlParser.parse(source);

        //than we transform those maps to model objects
        Executable executable = transformToExecutable(operationName, parsedSlang);

        Map<String, SlangPreCompiledMetaData.SlangFileType> dependencies;
        boolean hasDependencies = MapUtils.isNotEmpty(parsedSlang.getImports());
        if(!hasDependencies){
            dependencies = new HashMap<>();
        } else{
            dependencies = dependenciesHelper.fetchDependenciesNonRecursive(executable);
        }

        return new SlangPreCompiledMetaData(executable, dependencies);
    }

}