/*
 *  Copyright 2016 Alexey Andreev.
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
package org.teavm.ast.decompilation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.teavm.ast.AsyncMethodNode;
import org.teavm.ast.AsyncMethodPart;
import org.teavm.ast.BlockStatement;
import org.teavm.ast.GotoPartStatement;
import org.teavm.ast.IdentifiedStatement;
import org.teavm.ast.RegularMethodNode;
import org.teavm.ast.SequentialStatement;
import org.teavm.ast.Statement;
import org.teavm.ast.TryCatchStatement;
import org.teavm.ast.VariableNode;
import org.teavm.ast.WhileStatement;
import org.teavm.ast.optimization.Optimizer;
import org.teavm.common.Graph;
import org.teavm.common.GraphIndexer;
import org.teavm.common.Loop;
import org.teavm.common.LoopGraph;
import org.teavm.common.RangeTree;
import org.teavm.model.BasicBlock;
import org.teavm.model.ClassHolderSource;
import org.teavm.model.Instruction;
import org.teavm.model.MethodHolder;
import org.teavm.model.MethodReference;
import org.teavm.model.Program;
import org.teavm.model.TextLocation;
import org.teavm.model.TryCatchBlock;
import org.teavm.model.instructions.BinaryBranchingInstruction;
import org.teavm.model.instructions.BranchingInstruction;
import org.teavm.model.instructions.JumpInstruction;
import org.teavm.model.text.ListingBuilder;
import org.teavm.model.util.AsyncProgramSplitter;
import org.teavm.model.util.ProgramUtils;
import org.teavm.model.util.TypeInferer;

public class Decompiler {
    private ClassHolderSource classSource;
    private Graph graph;
    private LoopGraph loopGraph;
    private GraphIndexer indexer;
    private int[] loops;
    private int[] loopSuccessors;
    private Block[] blockMap;
    private boolean[] exceptionHandlers;
    private int lastBlockId;
    private RangeTree codeTree;
    private RangeTree.Node currentNode;
    private RangeTree.Node parentNode;
    private Set<MethodReference> splitMethods;
    private List<TryCatchBookmark> tryCatchBookmarks = new ArrayList<>();
    private Deque<Block> stack;
    private Program program;
    private boolean friendlyToDebugger;

    public Decompiler(ClassHolderSource classSource, Set<MethodReference> splitMethods, boolean friendlyToDebugger) {
        this.classSource = classSource;
        this.splitMethods = splitMethods;
        this.friendlyToDebugger = friendlyToDebugger;
    }

    static class Block {
        Block parent;
        int parentOffset;
        final IdentifiedStatement statement;
        final List<Statement> body;
        final int end;
        final int start;
        final List<TryCatchBookmark> tryCatches = new ArrayList<>();
        int nodeToRestore;
        Block nodeBackup;
        int nodeToRestore2 = -1;
        Block nodeBackup2;

        Block(IdentifiedStatement statement, List<Statement> body, int start, int end) {
            this.statement = statement;
            this.body = body;
            this.start = start;
            this.end = end;
        }

        void installTo(int index, Block[] blockMap) {
            if (nodeBackup == null) {
                nodeToRestore = index;
                nodeBackup = blockMap[index];
            } else {
                nodeToRestore2 = index;
                nodeBackup2 = blockMap[index];
            }
            blockMap[index] = this;
        }

        void removeFrom(Block[] blockMap) {
            blockMap[nodeToRestore] = nodeBackup;
            if (nodeToRestore2 >= 0) {
                blockMap[nodeToRestore2] = nodeBackup2;
            }
        }
    }

    static class TryCatchBookmark {
        Block block;
        int offset;
        String exceptionType;
        Integer exceptionVariable;
        int exceptionHandler;
    }

    public RegularMethodNode decompileRegular(MethodHolder method) {
        RegularMethodNode methodNode = new RegularMethodNode(method.getReference());
        Program program = method.getProgram();
        int[] targetBlocks = new int[program.basicBlockCount()];
        Arrays.fill(targetBlocks, -1);
        try {
            methodNode.setBody(getRegularMethodStatement(program, targetBlocks, false).getStatement());
        } catch (RuntimeException e) {
            StringBuilder sb = new StringBuilder("Error decompiling method " + method.getReference() + ":\n");
            sb.append(new ListingBuilder().buildListing(program, "  "));
            throw new DecompilationException(sb.toString(), e);
        }

        TypeInferer typeInferer = new TypeInferer();
        typeInferer.inferTypes(program, method.getReference());
        for (int i = 0; i < program.variableCount(); ++i) {
            VariableNode variable = new VariableNode(program.variableAt(i).getRegister(), typeInferer.typeOf(i));
            variable.setName(program.variableAt(i).getDebugName());
            methodNode.getVariables().add(variable);
        }

        Optimizer optimizer = new Optimizer();
        optimizer.optimize(methodNode, method.getProgram(), friendlyToDebugger);
        methodNode.getModifiers().addAll(method.getModifiers());

        return methodNode;
    }

    public AsyncMethodNode decompileAsync(MethodHolder method) {
        AsyncMethodNode node = new AsyncMethodNode(method.getReference());
        AsyncProgramSplitter splitter = new AsyncProgramSplitter(classSource, splitMethods);
        splitter.split(method.getProgram());
        for (int i = 0; i < splitter.size(); ++i) {
            AsyncMethodPart part;
            try {
                part = getRegularMethodStatement(splitter.getProgram(i), splitter.getBlockSuccessors(i), i > 0);
            } catch (RuntimeException e) {
                StringBuilder sb = new StringBuilder("Error decompiling method " + method.getReference()
                        + " part " + i + ":\n");
                sb.append(new ListingBuilder().buildListing(splitter.getProgram(i), "  "));
                throw new DecompilationException(sb.toString(), e);
            }
            node.getBody().add(part);
        }

        Program program = method.getProgram();
        TypeInferer typeInferer = new TypeInferer();
        typeInferer.inferTypes(program, method.getReference());
        for (int i = 0; i < program.variableCount(); ++i) {
            VariableNode variable = new VariableNode(program.variableAt(i).getRegister(), typeInferer.typeOf(i));
            variable.setName(program.variableAt(i).getDebugName());
            node.getVariables().add(variable);
        }

        Optimizer optimizer = new Optimizer();
        optimizer.optimize(node, splitter, friendlyToDebugger);
        node.getModifiers().addAll(method.getModifiers());

        return node;
    }

    private AsyncMethodPart getRegularMethodStatement(Program program, int[] targetBlocks, boolean async) {
        AsyncMethodPart result = new AsyncMethodPart();
        lastBlockId = 1;
        graph = ProgramUtils.buildControlFlowGraph(program);
        int[] weights = new int[graph.size()];
        for (int i = 0; i < weights.length; ++i) {
            weights[i] = program.basicBlockAt(i).instructionCount();
        }
        int[] priorities = new int[graph.size()];
        for (int i = 0; i < targetBlocks.length; ++i) {
            if (targetBlocks[i] >= 0) {
                priorities[i] = 1;
            }
        }
        indexer = new GraphIndexer(graph, weights, priorities);
        graph = indexer.getGraph();
        loopGraph = new LoopGraph(this.graph);
        unflatCode();
        blockMap = new Block[program.basicBlockCount() * 2 + 1];
        stack = new ArrayDeque<>();
        this.program = program;
        BlockStatement rootStmt = new BlockStatement();
        rootStmt.setId("root");
        stack.push(new Block(rootStmt, rootStmt.getBody(), -1, -1));
        StatementGenerator generator = new StatementGenerator();
        generator.classSource = classSource;
        generator.program = program;
        generator.blockMap = blockMap;
        generator.indexer = indexer;
        parentNode = codeTree.getRoot();
        currentNode = parentNode.getFirstChild();
        generator.async = async;
        fillExceptionHandlers(program);
        for (int i = 0; i < this.graph.size(); ++i) {
            int node = i < indexer.size() ? indexer.nodeAt(i) : -1;
            int next = i + 1;
            int head = loops[i];
            if (head != -1 && loopSuccessors[head] == next) {
                next = head;
            }

            if (node >= 0) {
                generator.currentBlock = program.basicBlockAt(node);
                int tmp = indexer.nodeAt(next);
                generator.nextBlock = tmp >= 0 && next < indexer.size() ? program.basicBlockAt(tmp) : null;
            }

            Block block = stack.peek();

            while (parentNode.getEnd() == i) {
                currentNode = parentNode.getNext();
                parentNode = parentNode.getParent();
            }
            for (Block newBlock : createBlocks(i)) {
                block.body.add(newBlock.statement);
                newBlock.parent = block;
                newBlock.parentOffset = block.body.size();
                stack.push(newBlock);
                block = newBlock;
            }
            createNewBookmarks(generator.currentBlock.getTryCatchBlocks());

            if (node >= 0) {
                generator.statements.clear();
                TextLocation lastLocation = null;
                for (Instruction insn : generator.currentBlock) {
                    if (insn.getLocation() != null && lastLocation != insn.getLocation()) {
                        lastLocation = insn.getLocation();
                    }
                    if (insn.getLocation() != null) {
                        generator.setCurrentLocation(lastLocation);
                    }
                    insn.acceptVisitor(generator);
                }
                if (targetBlocks[node] >= 0) {
                    GotoPartStatement stmt = new GotoPartStatement();
                    stmt.setPart(targetBlocks[node]);
                    generator.statements.add(stmt);
                }

                block.body.addAll(generator.statements);
            }

            while (block.end == i + 1) {
                Block oldBlock = block;
                stack.pop();
                block = stack.peek();
                if (block.start >= 0) {
                    int mappedStart = indexer.nodeAt(block.start);
                    if (blockMap[mappedStart] == oldBlock) {
                        blockMap[mappedStart] = block;
                    }
                }

                for (int j = 0; j < oldBlock.tryCatches.size(); ++j) {
                    TryCatchBookmark bookmark = oldBlock.tryCatches.get(j);
                    TryCatchStatement tryCatchStmt = new TryCatchStatement();
                    tryCatchStmt.setExceptionType(bookmark.exceptionType);
                    tryCatchStmt.setExceptionVariable(bookmark.exceptionVariable);
                    tryCatchStmt.getHandler().add(generator.generateJumpStatement(
                            program.basicBlockAt(bookmark.exceptionHandler)));
                    List<Statement> blockPart = oldBlock.body.subList(bookmark.offset, oldBlock.body.size());
                    tryCatchStmt.getProtectedBody().addAll(blockPart);
                    blockPart.clear();
                    if (!tryCatchStmt.getProtectedBody().isEmpty()) {
                        blockPart.add(tryCatchStmt);
                    }
                }

                tryCatchBookmarks.subList(tryCatchBookmarks.size() - oldBlock.tryCatches.size(),
                        tryCatchBookmarks.size()).clear();
                oldBlock.tryCatches.clear();
                oldBlock.removeFrom(blockMap);
            }

            if (generator.nextBlock != null && !isTrivialBlock(generator.nextBlock)) {
                closeExpiredBookmarks(generator, generator.nextBlock.getTryCatchBlocks());
            }
        }

        SequentialStatement resultBody = new SequentialStatement();
        resultBody.getSequence().addAll(rootStmt.getBody());
        result.setStatement(resultBody);
        return result;
    }

    private void fillExceptionHandlers(Program program) {
        exceptionHandlers = new boolean[program.basicBlockCount()];
        for (int i = 0; i < exceptionHandlers.length; ++i) {
            BasicBlock block = program.basicBlockAt(i);
            for (TryCatchBlock tryCatch : block.getTryCatchBlocks()) {
                exceptionHandlers[tryCatch.getHandler().getIndex()] = true;
            }
        }
    }

    private boolean isTrivialBlock(BasicBlock block) {
        if (exceptionHandlers[block.getIndex()]) {
            return false;
        }
        if (block.instructionCount() != 1 || block.getExceptionVariable() != null) {
            return false;
        }
        Instruction instruction = block.getLastInstruction();
        return instruction instanceof JumpInstruction
                || instruction instanceof BranchingInstruction
                || instruction instanceof BinaryBranchingInstruction;
    }

    private void closeExpiredBookmarks(StatementGenerator generator, List<TryCatchBlock> tryCatchBlocks) {
        tryCatchBlocks = new ArrayList<>(tryCatchBlocks);
        Collections.reverse(tryCatchBlocks);

        // Find which try catch blocks have remained since the previous basic block
        int sz = Math.min(tryCatchBlocks.size(), tryCatchBookmarks.size());
        int start;
        for (start = 0; start < sz; ++start) {
            TryCatchBlock tryCatch = tryCatchBlocks.get(start);
            TryCatchBookmark bookmark = tryCatchBookmarks.get(start);
            if (tryCatch.getHandler().getIndex() != bookmark.exceptionHandler) {
                break;
            }
            if (!Objects.equals(tryCatch.getExceptionType(), bookmark.exceptionType)) {
                break;
            }
        }

        // Close old bookmarks
        List<TryCatchBookmark> removedBookmarks = new ArrayList<>();
        for (int i = tryCatchBookmarks.size() - 1; i >= start; --i) {
            TryCatchBookmark bookmark = tryCatchBookmarks.get(i);
            bookmark.block.tryCatches.remove(bookmark);
            removedBookmarks.add(bookmark);
        }

        Collections.reverse(removedBookmarks);
        for (TryCatchBookmark bookmark : removedBookmarks) {
            Block block = stack.peek();
            while (block != bookmark.block) {
                if (block.body.size() > 1) {
                    TryCatchStatement tryCatchStmt = new TryCatchStatement();
                    tryCatchStmt.setExceptionType(bookmark.exceptionType);
                    tryCatchStmt.setExceptionVariable(bookmark.exceptionVariable);
                    tryCatchStmt.getHandler().add(generator.generateJumpStatement(
                            program.basicBlockAt(bookmark.exceptionHandler)));
                    List<Statement> body = block.body.subList(0, block.body.size() - 1);
                    tryCatchStmt.getProtectedBody().addAll(body);
                    body.clear();
                    body.add(tryCatchStmt);
                }
                block = block.parent;
            }
            TryCatchStatement tryCatchStmt = new TryCatchStatement();
            tryCatchStmt.setExceptionType(bookmark.exceptionType);
            tryCatchStmt.setExceptionVariable(bookmark.exceptionVariable);
            Statement jumpToHandler = generator.generateJumpStatement(program.basicBlockAt(bookmark.exceptionHandler));
            if (jumpToHandler != null) {
                tryCatchStmt.getHandler().add(jumpToHandler);
            }
            List<Statement> blockPart = block.body.subList(bookmark.offset, block.body.size());
            tryCatchStmt.getProtectedBody().addAll(blockPart);
            blockPart.clear();
            if (!tryCatchStmt.getProtectedBody().isEmpty()) {
                blockPart.add(tryCatchStmt);
            }
            block.tryCatches.remove(bookmark);
        }

        tryCatchBookmarks.subList(start, tryCatchBookmarks.size()).clear();
    }

    private void createNewBookmarks(List<TryCatchBlock> tryCatchBlocks) {
        // Add new bookmarks
        for (int i = tryCatchBookmarks.size(); i < tryCatchBlocks.size(); ++i) {
            TryCatchBlock tryCatch = tryCatchBlocks.get(tryCatchBlocks.size() - 1 - i);
            TryCatchBookmark bookmark = new TryCatchBookmark();
            bookmark.block = stack.peek();
            bookmark.offset = bookmark.block.body.size();
            bookmark.exceptionHandler = tryCatch.getHandler().getIndex();
            bookmark.exceptionType = tryCatch.getExceptionType();
            bookmark.exceptionVariable = tryCatch.getHandler().getExceptionVariable() != null
                    ? tryCatch.getHandler().getExceptionVariable().getIndex() : null;
            bookmark.block.tryCatches.add(bookmark);
            tryCatchBookmarks.add(bookmark);
        }
    }

    private List<Block> createBlocks(int start) {
        List<Block> result = new ArrayList<>();
        while (currentNode != null && currentNode.getStart() == start) {
            Block block;
            IdentifiedStatement statement;
            boolean loop = false;
            if (loopSuccessors[start] == currentNode.getEnd() || isSingleBlockLoop(start)) {
                WhileStatement whileStatement = new WhileStatement();
                statement = whileStatement;
                block = new Block(statement, whileStatement.getBody(), start, currentNode.getEnd());
                loop = true;
            } else {
                BlockStatement blockStatement = new BlockStatement();
                statement = blockStatement;
                block = new Block(statement, blockStatement.getBody(), start, currentNode.getEnd());
            }
            result.add(block);
            int mappedIndex = indexer.nodeAt(currentNode.getEnd());
            if (mappedIndex >= 0 && (blockMap[mappedIndex] == null
                    || !(blockMap[mappedIndex].statement instanceof WhileStatement))) {
                block.installTo(mappedIndex, blockMap);
            }
            if (loop) {
                block.installTo(indexer.nodeAt(start), blockMap);
            }
            parentNode = currentNode;
            currentNode = currentNode.getFirstChild();
        }
        for (Block block : result) {
            block.statement.setId("block" + lastBlockId++);
        }
        return result;
    }

    private boolean isSingleBlockLoop(int index) {
        for (int succ : graph.outgoingEdges(index)) {
            if (succ == index) {
                return true;
            }
        }
        return false;
    }

    private void unflatCode() {
        Graph graph = this.graph;
        int sz = graph.size();

        // Find where each loop ends
        //
        int[] loopSuccessors = new int[sz];
        Arrays.fill(loopSuccessors, sz + 1);
        for (int node = 0; node < sz; ++node) {
            Loop loop = loopGraph.loopAt(node);
            while (loop != null) {
                loopSuccessors[loop.getHead()] = node + 1;
                loop = loop.getParent();
            }
        }

        // For each node find head of loop this node belongs to.
        //
        int[] loops = new int[sz];
        Arrays.fill(loops, -1);
        for (int head = 0; head < sz; ++head) {
            int end = loopSuccessors[head];
            if (end > sz) {
                continue;
            }
            for (int node = head + 1; node < end; ++node) {
                loops[node] = head;
            }
        }

        List<RangeTree.Range> ranges = new ArrayList<>();
        for (int node = 0; node < sz; ++node) {
            if (loopSuccessors[node] <= sz) {
                ranges.add(new RangeTree.Range(node, loopSuccessors[node]));
            }
            int start = sz;
            for (int prev : graph.incomingEdges(node)) {
                start = Math.min(start, prev);
            }
            if (start < node - 1) {
                ranges.add(new RangeTree.Range(start, node));
            }
        }
        for (int node = 0; node < sz; ++node) {
            if (isSingleBlockLoop(node)) {
                ranges.add(new RangeTree.Range(node, node + 1));
            }
        }
        codeTree = new RangeTree(sz + 1, ranges);
        this.loopSuccessors = loopSuccessors;
        this.loops = loops;
    }
}
