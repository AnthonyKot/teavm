/*
 *  Copyright 2018 Alexey Andreev.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.teavm.backend.c;

import com.carrotsearch.hppc.ObjectByteHashMap;
import com.carrotsearch.hppc.ObjectByteMap;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.teavm.ast.InvocationExpr;
import org.teavm.ast.decompilation.Decompiler;
import org.teavm.backend.c.analyze.CDependencyListener;
import org.teavm.backend.c.analyze.InteropDependencyListener;
import org.teavm.backend.c.generate.BufferedCodeWriter;
import org.teavm.backend.c.generate.CallSiteGenerator;
import org.teavm.backend.c.generate.ClassGenerator;
import org.teavm.backend.c.generate.CodeGenerationVisitor;
import org.teavm.backend.c.generate.CodeGeneratorUtil;
import org.teavm.backend.c.generate.CodeWriter;
import org.teavm.backend.c.generate.GenerationContext;
import org.teavm.backend.c.generate.IncludeManager;
import org.teavm.backend.c.generate.NameProvider;
import org.teavm.backend.c.generate.OutputFileUtil;
import org.teavm.backend.c.generate.SimpleIncludeManager;
import org.teavm.backend.c.generate.StringPool;
import org.teavm.backend.c.generate.StringPoolGenerator;
import org.teavm.backend.c.generators.ArrayGenerator;
import org.teavm.backend.c.generators.Generator;
import org.teavm.backend.c.generators.GeneratorFactory;
import org.teavm.backend.c.intrinsic.AddressIntrinsic;
import org.teavm.backend.c.intrinsic.AllocatorIntrinsic;
import org.teavm.backend.c.intrinsic.ConsoleIntrinsic;
import org.teavm.backend.c.intrinsic.ExceptionHandlingIntrinsic;
import org.teavm.backend.c.intrinsic.FunctionIntrinsic;
import org.teavm.backend.c.intrinsic.GCIntrinsic;
import org.teavm.backend.c.intrinsic.IntegerIntrinsic;
import org.teavm.backend.c.intrinsic.Intrinsic;
import org.teavm.backend.c.intrinsic.IntrinsicContext;
import org.teavm.backend.c.intrinsic.IntrinsicFactory;
import org.teavm.backend.c.intrinsic.LongIntrinsic;
import org.teavm.backend.c.intrinsic.MutatorIntrinsic;
import org.teavm.backend.c.intrinsic.PlatformClassIntrinsic;
import org.teavm.backend.c.intrinsic.PlatformClassMetadataIntrinsic;
import org.teavm.backend.c.intrinsic.PlatformIntrinsic;
import org.teavm.backend.c.intrinsic.PlatformObjectIntrinsic;
import org.teavm.backend.c.intrinsic.RuntimeClassIntrinsic;
import org.teavm.backend.c.intrinsic.ShadowStackIntrinsic;
import org.teavm.backend.c.intrinsic.StringsIntrinsic;
import org.teavm.backend.c.intrinsic.StructureIntrinsic;
import org.teavm.backend.lowlevel.dependency.ExceptionHandlingDependencyListener;
import org.teavm.backend.lowlevel.transform.CoroutineTransformation;
import org.teavm.dependency.ClassDependency;
import org.teavm.dependency.DependencyAnalyzer;
import org.teavm.dependency.DependencyListener;
import org.teavm.interop.Address;
import org.teavm.interop.PlatformMarkers;
import org.teavm.interop.Structure;
import org.teavm.model.BasicBlock;
import org.teavm.model.ClassHierarchy;
import org.teavm.model.ClassHolder;
import org.teavm.model.ClassHolderTransformer;
import org.teavm.model.ClassReader;
import org.teavm.model.FieldReader;
import org.teavm.model.FieldReference;
import org.teavm.model.Instruction;
import org.teavm.model.ListableClassHolderSource;
import org.teavm.model.ListableClassReaderSource;
import org.teavm.model.MethodDescriptor;
import org.teavm.model.MethodHolder;
import org.teavm.model.MethodReader;
import org.teavm.model.MethodReference;
import org.teavm.model.Program;
import org.teavm.model.ValueType;
import org.teavm.model.classes.TagRegistry;
import org.teavm.model.classes.VirtualTableProvider;
import org.teavm.model.instructions.CloneArrayInstruction;
import org.teavm.model.instructions.InvocationType;
import org.teavm.model.instructions.InvokeInstruction;
import org.teavm.model.lowlevel.Characteristics;
import org.teavm.model.lowlevel.ClassInitializerEliminator;
import org.teavm.model.lowlevel.ClassInitializerTransformer;
import org.teavm.model.lowlevel.ExportDependencyListener;
import org.teavm.model.lowlevel.NullCheckInsertion;
import org.teavm.model.lowlevel.NullCheckTransformation;
import org.teavm.model.lowlevel.ShadowStackTransformer;
import org.teavm.model.transformation.ClassPatch;
import org.teavm.model.util.AsyncMethodFinder;
import org.teavm.runtime.Allocator;
import org.teavm.runtime.EventQueue;
import org.teavm.runtime.ExceptionHandling;
import org.teavm.runtime.Fiber;
import org.teavm.runtime.RuntimeArray;
import org.teavm.runtime.RuntimeClass;
import org.teavm.runtime.RuntimeObject;
import org.teavm.vm.BuildTarget;
import org.teavm.vm.TeaVMEntryPoint;
import org.teavm.vm.TeaVMTarget;
import org.teavm.vm.TeaVMTargetController;
import org.teavm.vm.spi.TeaVMHostExtension;

public class CTarget implements TeaVMTarget, TeaVMCHost {
    private static final Set<MethodReference> VIRTUAL_METHODS = new HashSet<>(Arrays.asList(
            new MethodReference(Object.class, "clone", Object.class)
    ));
    private static final MethodReference STRING_CONSTRUCTOR = new MethodReference(String.class,
            "<init>", char[].class, void.class);

    private TeaVMTargetController controller;
    private ClassInitializerEliminator classInitializerEliminator;
    private ClassInitializerTransformer classInitializerTransformer;
    private ShadowStackTransformer shadowStackTransformer;
    private NullCheckInsertion nullCheckInsertion;
    private NullCheckTransformation nullCheckTransformation;
    private ExportDependencyListener exportDependencyListener = new ExportDependencyListener();
    private int minHeapSize = 32 * 1024 * 1024;
    private List<IntrinsicFactory> intrinsicFactories = new ArrayList<>();
    private List<GeneratorFactory> generatorFactories = new ArrayList<>();
    private Characteristics characteristics;
    private Map<String, ShadowStackTransformer> shadowStackTransformersByClass = new HashMap<>();
    private Set<MethodReference> asyncMethods;
    private boolean incremental;

    public void setMinHeapSize(int minHeapSize) {
        this.minHeapSize = minHeapSize;
    }

    public void setIncremental(boolean incremental) {
        this.incremental = incremental;
    }

    @Override
    public List<ClassHolderTransformer> getTransformers() {
        List<ClassHolderTransformer> transformers = new ArrayList<>();
        transformers.add(new ClassPatch());
        transformers.add(new CDependencyListener());
        return transformers;
    }

    @Override
    public List<DependencyListener> getDependencyListeners() {
        return Arrays.asList(new CDependencyListener(), exportDependencyListener, new InteropDependencyListener());
    }

    @Override
    public void setController(TeaVMTargetController controller) {
        this.controller = controller;
        characteristics = new Characteristics(controller.getUnprocessedClassSource());
        classInitializerEliminator = new ClassInitializerEliminator(controller.getUnprocessedClassSource());
        classInitializerTransformer = new ClassInitializerTransformer();
        shadowStackTransformer = new ShadowStackTransformer(characteristics);
        nullCheckInsertion = new NullCheckInsertion(characteristics);
        nullCheckTransformation = new NullCheckTransformation();

        controller.addVirtualMethods(VIRTUAL_METHODS::contains);
    }

    @Override
    public List<TeaVMHostExtension> getHostExtensions() {
        return Collections.singletonList(this);
    }

    @Override
    public void addIntrinsic(IntrinsicFactory intrinsicFactory) {
        intrinsicFactories.add(intrinsicFactory);
    }

    @Override
    public void addGenerator(GeneratorFactory generatorFactory) {
        generatorFactories.add(generatorFactory);
    }

    @Override
    public boolean requiresRegisterAllocation() {
        return true;
    }

    @Override
    public void contributeDependencies(DependencyAnalyzer dependencyAnalyzer) {
        dependencyAnalyzer.linkMethod(new MethodReference(Allocator.class, "allocate",
                RuntimeClass.class, Address.class)).use();
        dependencyAnalyzer.linkMethod(new MethodReference(Allocator.class, "allocateArray",
                RuntimeClass.class, int.class, Address.class)).use();
        dependencyAnalyzer.linkMethod(new MethodReference(Allocator.class, "allocateMultiArray",
                RuntimeClass.class, Address.class, int.class, RuntimeArray.class)).use();

        dependencyAnalyzer.linkMethod(new MethodReference(Allocator.class, "<clinit>", void.class)).use();

        dependencyAnalyzer.linkMethod(new MethodReference(ExceptionHandling.class, "throwException",
                Throwable.class, void.class)).use();
        dependencyAnalyzer.linkMethod(new MethodReference(ExceptionHandling.class, "throwClassCastException",
                void.class)).use();
        dependencyAnalyzer.linkMethod(new MethodReference(ExceptionHandling.class, "throwNullPointerException",
                void.class)).use();
        dependencyAnalyzer.linkMethod(new MethodReference(NullPointerException.class, "<init>", void.class))
                .propagate(0, NullPointerException.class.getName())
                .use();

        dependencyAnalyzer.linkMethod(new MethodReference(ExceptionHandling.class, "catchException",
                Throwable.class)).use();

        dependencyAnalyzer.linkClass("java.lang.String");
        dependencyAnalyzer.linkClass("java.lang.Class");
        dependencyAnalyzer.linkField(new FieldReference("java.lang.String", "hashCode"));
        dependencyAnalyzer.linkMethod(STRING_CONSTRUCTOR)
                .propagate(0, "java.lang.String")
                .propagate(1, "[C")
                .use();

        ClassDependency runtimeClassDep = dependencyAnalyzer.linkClass(RuntimeClass.class.getName());
        ClassDependency runtimeObjectDep = dependencyAnalyzer.linkClass(RuntimeObject.class.getName());
        ClassDependency runtimeArrayDep = dependencyAnalyzer.linkClass(RuntimeArray.class.getName());
        for (ClassDependency classDep : Arrays.asList(runtimeClassDep, runtimeObjectDep, runtimeArrayDep)) {
            for (FieldReader field : classDep.getClassReader().getFields()) {
                dependencyAnalyzer.linkField(field.getReference());
            }
        }

        dependencyAnalyzer.linkMethod(new MethodReference(Fiber.class, "isResuming", boolean.class)).use();
        dependencyAnalyzer.linkMethod(new MethodReference(Fiber.class, "isSuspending", boolean.class)).use();
        dependencyAnalyzer.linkMethod(new MethodReference(Fiber.class, "current", Fiber.class)).use();
        dependencyAnalyzer.linkMethod(new MethodReference(Fiber.class, "startMain", String[].class, void.class)).use();
        dependencyAnalyzer.linkMethod(new MethodReference(EventQueue.class, "process", void.class)).use();
        dependencyAnalyzer.linkMethod(new MethodReference(Thread.class, "setCurrentThread", Thread.class,
                void.class)).use();

        ClassReader fiberClass = dependencyAnalyzer.getClassSource().get(Fiber.class.getName());
        for (MethodReader method : fiberClass.getMethods()) {
            if (method.getName().startsWith("pop") || method.getName().equals("push")) {
                dependencyAnalyzer.linkMethod(method.getReference()).use();
            }
        }

        dependencyAnalyzer.addDependencyListener(new ExceptionHandlingDependencyListener());
    }

    @Override
    public void analyzeBeforeOptimizations(ListableClassReaderSource classSource) {
        AsyncMethodFinder asyncFinder = new AsyncMethodFinder(controller.getDependencyInfo().getCallGraph(),
                controller.getDiagnostics());
        asyncFinder.find(classSource);
        asyncMethods = new HashSet<>(asyncFinder.getAsyncMethods());
        asyncMethods.addAll(asyncFinder.getAsyncFamilyMethods());
    }

    @Override
    public void beforeOptimizations(Program program, MethodReader method) {
        nullCheckInsertion.transformProgram(program, method.getReference());
    }

    @Override
    public void afterOptimizations(Program program, MethodReader method) {
        classInitializerEliminator.apply(program);
        classInitializerTransformer.transform(program);
        nullCheckTransformation.apply(program, method.getResultType());
        new CoroutineTransformation(controller.getUnprocessedClassSource(), asyncMethods)
                .apply(program, method.getReference());
        getShadowStackTransformer(method.getOwnerName()).apply(program, method);
    }

    private ShadowStackTransformer getShadowStackTransformer(String className) {
        if (!incremental) {
            return shadowStackTransformer;
        }
        return shadowStackTransformersByClass.computeIfAbsent(className,
                c -> new ShadowStackTransformer(characteristics));
    }

    @Override
    public void emit(ListableClassHolderSource classes, BuildTarget buildTarget, String outputName) throws IOException {
        VirtualTableProvider vtableProvider = createVirtualTableProvider(classes);
        ClassHierarchy hierarchy = new ClassHierarchy(classes);
        TagRegistry tagRegistry = new TagRegistry(classes, hierarchy);
        StringPool stringPool = new StringPool();

        Decompiler decompiler = new Decompiler(classes, new HashSet<>(), false, true);
        Characteristics characteristics = new Characteristics(controller.getUnprocessedClassSource());

        NameProvider nameProvider = new NameProvider(controller.getUnprocessedClassSource());

        List<Intrinsic> intrinsics = new ArrayList<>();
        intrinsics.add(new ShadowStackIntrinsic());
        intrinsics.add(new AddressIntrinsic());
        intrinsics.add(new AllocatorIntrinsic());
        intrinsics.add(new StructureIntrinsic(characteristics));
        intrinsics.add(new PlatformIntrinsic());
        intrinsics.add(new PlatformObjectIntrinsic());
        intrinsics.add(new PlatformClassIntrinsic());
        intrinsics.add(new PlatformClassMetadataIntrinsic());
        intrinsics.add(new GCIntrinsic());
        intrinsics.add(new MutatorIntrinsic());
        intrinsics.add(new ExceptionHandlingIntrinsic());
        intrinsics.add(new FunctionIntrinsic(characteristics, exportDependencyListener.getResolvedMethods()));
        intrinsics.add(new RuntimeClassIntrinsic());
        intrinsics.add(new FiberIntrinsic());
        intrinsics.add(new LongIntrinsic());
        intrinsics.add(new IntegerIntrinsic());
        intrinsics.add(new StringsIntrinsic());
        intrinsics.add(new ConsoleIntrinsic());

        List<Generator> generators = new ArrayList<>();
        generators.add(new ArrayGenerator());

        GenerationContext context = new GenerationContext(vtableProvider, characteristics,
                controller.getDependencyInfo(), stringPool, nameProvider, controller.getDiagnostics(), classes,
                intrinsics, generators, asyncMethods::contains, buildTarget, incremental);

        BufferedCodeWriter runtimeWriter = new BufferedCodeWriter();
        BufferedCodeWriter runtimeHeaderWriter = new BufferedCodeWriter();
        emitResource(runtimeWriter, "runtime.c");

        runtimeHeaderWriter.println("#pragma once");
        if (incremental) {
            runtimeHeaderWriter.println("#define TEAVM_INCREMENTAL true");
        }
        emitResource(runtimeHeaderWriter, "runtime.h");

        ClassGenerator classGenerator = new ClassGenerator(context, controller.getUnprocessedClassSource(),
                tagRegistry, decompiler);
        IntrinsicFactoryContextImpl intrinsicFactoryContext = new IntrinsicFactoryContextImpl(
                controller.getUnprocessedClassSource(), controller.getClassLoader(), controller.getServices(),
                controller.getProperties());
        for (IntrinsicFactory intrinsicFactory : intrinsicFactories) {
            context.addIntrinsic(intrinsicFactory.createIntrinsic(intrinsicFactoryContext));
        }
        for (GeneratorFactory generatorFactory : generatorFactories) {
            context.addGenerator(generatorFactory.createGenerator(intrinsicFactoryContext));
        }

        generateClasses(classes, classGenerator, buildTarget);
        generateSpecialFunctions(context, runtimeWriter);
        OutputFileUtil.write(runtimeWriter, "runtime.c", buildTarget);
        OutputFileUtil.write(runtimeHeaderWriter, "runtime.h", buildTarget);

        generateCallSites(buildTarget, context);
        generateStrings(buildTarget, stringPool);

        List<ValueType> types = classGenerator.getTypes().stream()
                .filter(c -> ClassGenerator.needsVirtualTable(characteristics, c))
                .collect(Collectors.toList());
        generateMainFile(context, classes, types, buildTarget);
        generateAllFile(classes, types, buildTarget);
    }


    private void emitResource(CodeWriter writer, String resourceName) {
        ClassLoader classLoader = CTarget.class.getClassLoader();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                classLoader.getResourceAsStream("org/teavm/backend/c/" + resourceName)))) {
            while (true) {
                String line = reader.readLine();
                if (line == null) {
                    break;
                }
                writer.println(line);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void generateClasses(ListableClassHolderSource classes, ClassGenerator classGenerator,
            BuildTarget buildTarget) throws IOException {
        List<String> classNames = sortClassNames(classes);

        classGenerator.prepare(classes);

        for (String className : classNames) {
            BufferedCodeWriter writer = new BufferedCodeWriter();
            BufferedCodeWriter headerWriter = new BufferedCodeWriter();
            ClassHolder cls = classes.get(className);
            if (cls != null) {
                classGenerator.generateClass(writer, headerWriter, cls, getShadowStackTransformer(className));
            }
            String name = ClassGenerator.fileName(className);
            OutputFileUtil.write(writer, name + ".c", buildTarget);
            OutputFileUtil.write(headerWriter, name + ".h", buildTarget);
        }

        for (ValueType type : classGenerator.getTypes()) {
            if (type instanceof ValueType.Object) {
                continue;
            }
            BufferedCodeWriter writer = new BufferedCodeWriter();
            BufferedCodeWriter headerWriter = new BufferedCodeWriter();
            classGenerator.generateType(writer, headerWriter, type);
            String name = ClassGenerator.fileName(type);
            OutputFileUtil.write(writer, name + ".c", buildTarget);
            OutputFileUtil.write(headerWriter, name + ".h", buildTarget);
        }
    }

    private List<String> sortClassNames(ListableClassReaderSource classes) {
        List<String> classNames = new ArrayList<>(classes.getClassNames().size());
        Deque<String> stack = new ArrayDeque<>(classes.getClassNames());
        ObjectByteMap<String> stateMap = new ObjectByteHashMap<>();

        while (!stack.isEmpty()) {
            String className = stack.pop();
            byte state = stateMap.getOrDefault(className, (byte) 0);
            switch (state) {
                case 0: {
                    stateMap.put(className, (byte) 1);
                    stack.push(className);
                    ClassReader cls = classes.get(className);
                    String parent = cls != null ? cls.getParent() : null;
                    if (parent == null) {
                        parent = RuntimeObject.class.getName();
                    }
                    if (!parent.equals(Structure.class.getName()) && stateMap.getOrDefault(parent, (byte) 0) == 0) {
                        stack.push(parent);
                    }
                    break;
                }
                case 1:
                    stateMap.put(className, (byte) 2);
                    classNames.add(className);
                    break;
            }
        }

        return classNames;
    }

    private void generateCallSites(BuildTarget buildTarget, GenerationContext context) throws IOException {
        BufferedCodeWriter writer = new BufferedCodeWriter();
        BufferedCodeWriter headerWriter = new BufferedCodeWriter();

        IncludeManager includes = new SimpleIncludeManager(writer);
        includes.init("callsites.c");
        includes.includePath("callsites.h");

        headerWriter.println("#pragma once");
        IncludeManager headerIncludes = new SimpleIncludeManager(headerWriter);
        headerIncludes.init("callsites.h");
        headerIncludes.includePath("runtime.h");
        headerIncludes.includeClass(CallSiteGenerator.CALL_SITE);

        if (incremental) {
            generateIncrementalCallSites(context, headerWriter);
        } else {
            generateFastCallSites(context, writer, includes, headerWriter);
        }

        OutputFileUtil.write(writer, "callsites.c", buildTarget);
        OutputFileUtil.write(headerWriter, "callsites.h", buildTarget);
    }

    private void generateFastCallSites(GenerationContext context, CodeWriter writer, IncludeManager includes,
            CodeWriter headerWriter) {
        String callSiteName = context.getNames().forClass(CallSiteGenerator.CALL_SITE);
        headerWriter.println("extern " + callSiteName + " teavm_callSites[];");
        headerWriter.println("#define TEAVM_FIND_CALLSITE(id, frame) (teavm_callSites + id)");

        new CallSiteGenerator(context, writer, includes, "teavm_callSites")
                .generate(shadowStackTransformer.getCallSites());
    }

    private void generateIncrementalCallSites(GenerationContext context, CodeWriter headerWriter) {
        String callSiteName = context.getNames().forClass(CallSiteGenerator.CALL_SITE);
        headerWriter.println("#define TEAVM_FIND_CALLSITE(id, frame) (((" + callSiteName
                + "*) ((void**) frame)[3]) + id)");
    }

    private void generateStrings(BuildTarget buildTarget, StringPool stringPool) throws IOException {
        BufferedCodeWriter writer = new BufferedCodeWriter();
        BufferedCodeWriter headerWriter = new BufferedCodeWriter();

        headerWriter.println("#pragma once");
        headerWriter.println("#include \"runtime.h\"");
        headerWriter.println("extern TeaVM_String teavm_stringPool[];");

        writer.println("#include \"strings.h\"");
        new StringPoolGenerator(writer).generate(stringPool.getStrings());

        OutputFileUtil.write(writer, "strings.c", buildTarget);
        OutputFileUtil.write(headerWriter, "strings.h", buildTarget);
    }

    private VirtualTableProvider createVirtualTableProvider(ListableClassHolderSource classes) {
        Set<MethodReference> virtualMethods = new LinkedHashSet<>();

        for (String className : classes.getClassNames()) {
            ClassHolder cls = classes.get(className);
            for (MethodHolder method : cls.getMethods()) {
                Program program = method.getProgram();
                if (program == null) {
                    continue;
                }
                for (int i = 0; i < program.basicBlockCount(); ++i) {
                    BasicBlock block = program.basicBlockAt(i);
                    for (Instruction insn : block) {
                        if (insn instanceof InvokeInstruction) {
                            InvokeInstruction invoke = (InvokeInstruction) insn;
                            if (invoke.getType() == InvocationType.VIRTUAL) {
                                virtualMethods.add(invoke.getMethod());
                            }
                        } else if (insn instanceof CloneArrayInstruction) {
                            virtualMethods.add(new MethodReference(Object.class, "clone", Object.class));
                        }
                    }
                }
            }
        }

        return new VirtualTableProvider(classes, virtualMethods, controller::isVirtual);
    }

    private void generateSpecialFunctions(GenerationContext context, CodeWriter writer) {
        IncludeManager includes = new SimpleIncludeManager(writer);
        includes.init("runtime.c");
        generateThrowCCE(context, writer, includes);
        generateAllocateStringArray(context, writer, includes);
        generateAllocateCharArray(context, writer, includes);
        generateCreateString(context, writer, includes);
    }

    private void generateThrowCCE(GenerationContext context, CodeWriter writer, IncludeManager includes) {
        includes.includeClass(ExceptionHandling.class.getName());
        writer.println("void* teavm_throwClassCastException() {").indent();
        String methodName = context.getNames().forMethod(new MethodReference(ExceptionHandling.class,
                "throwClassCastException", void.class));
        writer.println(methodName + "();");
        writer.println("return NULL;");
        writer.outdent().println("}");
    }

    private void generateAllocateStringArray(GenerationContext context, CodeWriter writer, IncludeManager includes) {
        includes.includeClass(Allocator.class.getName());
        includes.includeType(ValueType.parse(String[].class));
        writer.println("TeaVM_Array* teavm_allocateStringArray(int32_t size) {").indent();
        String allocateArrayName = context.getNames().forMethod(new MethodReference(Allocator.class,
                        "allocateArray", RuntimeClass.class, int.class, Address.class));
        String stringClassName = context.getNames().forClassInstance(ValueType.arrayOf(
                ValueType.object(String.class.getName())));
        writer.println("return (TeaVM_Array*) " + allocateArrayName + "(&" + stringClassName + ", size);");
        writer.outdent().println("}");
    }

    private void generateAllocateCharArray(GenerationContext context, CodeWriter writer, IncludeManager includes) {
        includes.includeType(ValueType.parse(char[].class));
        writer.println("TeaVM_Array* teavm_allocateCharArray(int32_t size) {").indent();
        String allocateArrayName = context.getNames().forMethod(new MethodReference(Allocator.class,
                "allocateArray", RuntimeClass.class, int.class, Address.class));
        String charClassName = context.getNames().forClassInstance(ValueType.arrayOf(ValueType.CHARACTER));
        writer.println("return (TeaVM_Array*) " + allocateArrayName + "(&" + charClassName + ", size);");
        writer.outdent().println("}");
    }

    private void generateCreateString(GenerationContext context, CodeWriter writer, IncludeManager includes) {
        NameProvider names = context.getNames();
        includes.includeClass(String.class.getName());
        writer.println("TeaVM_String* teavm_createString(TeaVM_Array* array) {").indent();
        writer.print("TeaVM_String* str = (TeaVM_String*) ").print(names.forMethod(CodeGenerationVisitor.ALLOC_METHOD))
                .print("(&").print(names.forClassInstance(ValueType.object("java.lang.String"))).println(");");
        writer.print(names.forMethod(STRING_CONSTRUCTOR)).println("(str, array);");
        writer.println("return str;");
        writer.outdent().println("}");
    }

    private void generateMainFile(GenerationContext context, ListableClassHolderSource classes,
            List<? extends ValueType> types, BuildTarget buildTarget) throws IOException {
        BufferedCodeWriter writer = new BufferedCodeWriter();
        IncludeManager includes = new SimpleIncludeManager(writer);
        includes.init("main.c");
        includes.includePath("runtime.h");
        includes.includePath("strings.h");

        generateArrayOfClassReferences(context, writer, includes, types);
        generateMain(context, writer, includes, classes, types);
        OutputFileUtil.write(writer, "main.c", buildTarget);
    }

    private void generateAllFile(ListableClassHolderSource classes, List<? extends ValueType> types,
            BuildTarget buildTarget) throws IOException {
        BufferedCodeWriter writer = new BufferedCodeWriter();
        IncludeManager includes = new SimpleIncludeManager(writer);
        includes.init("all.c");
        includes.includePath("runtime.c");
        includes.includePath("strings.c");
        includes.includePath("callsites.c");

        for (String className : classes.getClassNames()) {
            includes.includePath(ClassGenerator.fileName(className) + ".c");
        }
        for (ValueType type : types) {
            includes.includePath(ClassGenerator.fileName(type) + ".c");
        }

        includes.includePath("main.c");

        OutputFileUtil.write(writer, "all.c", buildTarget);
    }

    private void generateArrayOfClassReferences(GenerationContext context, CodeWriter writer, IncludeManager includes,
            List<? extends ValueType> types) {
        writer.print("static TeaVM_Class* teavm_classReferences[" + types.size() + "] = {").indent();
        boolean first = true;
        for (ValueType type : types) {
            if (!first) {
                writer.print(", ");
            }
            writer.println();
            first = false;
            String typeName = context.getNames().forClassInstance(type);
            includes.includeType(type);
            writer.print("(TeaVM_Class*) &" + typeName);
        }
        if (!first) {
            writer.println();
        }
        writer.outdent().println("};");
    }

    private void generateMain(GenerationContext context, CodeWriter writer, IncludeManager includes,
            ListableClassHolderSource classes, List<? extends ValueType> types) {
        writer.println("int main(int argc, char** argv) {").indent();

        writer.println("teavm_beforeInit();");
        writer.println("teavm_initHeap(" + minHeapSize + ");");
        generateVirtualTableHeaders(context, writer, types);
        generateStringPoolHeaders(context, writer);
        for (String className : classes.getClassNames()) {
            includes.includeClass(className);
            writer.println(context.getNames().forClassSystemInitializer(className) + "();");
        }
        writer.println("teavm_afterInitClasses();");
        generateStaticInitializerCalls(context, writer, includes, classes);
        writer.println(context.getNames().forClassInitializer("java.lang.String") + "();");
        generateFiberStart(context, writer, includes);

        writer.outdent().println("}");
    }

    private void generateStaticInitializerCalls(GenerationContext context, CodeWriter writer, IncludeManager includes,
            ListableClassReaderSource classes) {
        MethodDescriptor clinitDescriptor = new MethodDescriptor("<clinit>", ValueType.VOID);
        for (String className : classes.getClassNames()) {
            ClassReader cls = classes.get(className);
            if (!context.getCharacteristics().isStaticInit(cls.getName())
                    && !context.getCharacteristics().isStructure(cls.getName())) {
                continue;
            }


            if (cls.getMethod(clinitDescriptor) == null) {
                continue;
            }

            includes.includeClass(className);
            String clinitName = context.getNames().forMethod(new MethodReference(className, clinitDescriptor));
            writer.println(clinitName + "();");
        }
    }

    private void generateVirtualTableHeaders(GenerationContext context, CodeWriter writer,
            List<? extends ValueType> types) {
        writer.println("teavm_beforeClasses = (char*) teavm_classReferences[0];");
        writer.println("for (int i = 1; i < " + types.size() + "; ++i) {").indent();
        writer.println("char* c = (char*) teavm_classReferences[i];");
        writer.println("if (c < teavm_beforeClasses) teavm_beforeClasses = c;");
        writer.outdent().println("}");
        writer.println("teavm_beforeClasses -= 4096;");

        String classClassName = context.getNames().forClassInstance(ValueType.object("java.lang.Class"));
        writer.print("int32_t classHeader = TEAVM_PACK_CLASS(&" + classClassName + ") | ");
        CodeGeneratorUtil.writeIntValue(writer, RuntimeObject.GC_MARKED);
        writer.println(";");

        writer.println("for (int i = 0; i < " + types.size() + "; ++i) {").indent();
        writer.println("teavm_classReferences[i]->parent.header = classHeader;");
        writer.outdent().println("}");
    }

    private void generateStringPoolHeaders(GenerationContext context, CodeWriter writer) {
        String stringClassName = context.getNames().forClassInstance(ValueType.object("java.lang.String"));
        writer.print("int32_t stringHeader = TEAVM_PACK_CLASS(&" + stringClassName + ") | ");
        CodeGeneratorUtil.writeIntValue(writer, RuntimeObject.GC_MARKED);
        writer.println(";");

        int size = context.getStringPool().getStrings().size();
        writer.println("for (int i = 0; i < " + size + "; ++i) {").indent();
        writer.println("((TeaVM_Object*) (teavm_stringPool + i))->header = stringHeader;");
        writer.outdent().println("}");
    }

    private void generateFiberStart(GenerationContext context, CodeWriter writer, IncludeManager includes) {
        String startName = context.getNames().forMethod(new MethodReference(Fiber.class,
                "startMain", String[].class, void.class));
        String processName = context.getNames().forMethod(new MethodReference(EventQueue.class, "process", void.class));
        writer.println(startName + "(teavm_parseArguments(argc, argv));");
        writer.println(processName + "();");
        includes.includeClass(Fiber.class.getName());
        includes.includeClass(EventQueue.class.getName());
    }

    class FiberIntrinsic implements Intrinsic {
        @Override
        public boolean canHandle(MethodReference method) {
            if (!method.getClassName().equals(Fiber.class.getName())) {
                return false;
            }
            switch (method.getName()) {
                case "runMain":
                case "setCurrentThread":
                    return true;
                default:
                    return false;
            }
        }

        @Override
        public void apply(IntrinsicContext context, InvocationExpr invocation) {
            switch (invocation.getMethod().getName()) {
                case "runMain":
                    generateCallToMainMethod(context, invocation);
                    break;
                case "setCurrentThread":
                    context.includes().includeClass(Thread.class.getName());
                    String methodName = context.names().forMethod(new MethodReference(Thread.class,
                            "setCurrentThread", Thread.class, void.class));
                    context.writer().print(methodName).print("(");
                    context.emit(invocation.getArguments().get(0));
                    context.writer().print(")");
                    break;
            }
        }
    }

    private void generateCallToMainMethod(IntrinsicContext context, InvocationExpr invocation) {
        NameProvider names = context.names();
        TeaVMEntryPoint entryPoint = controller.getEntryPoints().get("main");
        if (entryPoint != null) {
            context.includes().includeClass(entryPoint.getMethod().getClassName());
            String mainMethod = names.forMethod(entryPoint.getMethod());
            context.writer().print(mainMethod + "(");
            context.emit(invocation.getArguments().get(0));
            context.writer().print(")");
        }
    }

    @Override
    public String[] getPlatformTags() {
        return new String[] { PlatformMarkers.C, PlatformMarkers.LOW_LEVEL };
    }

    @Override
    public boolean isAsyncSupported() {
        return true;
    }
}
