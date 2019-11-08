/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

package org.jetbrains.kotlin.backend.konan.optimizations

import org.jetbrains.kotlin.backend.konan.llvm.Lifetime
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.descriptors.isArrayWithFixedSizeItems
import org.jetbrains.kotlin.backend.konan.descriptors.isBuiltInOperator

internal object LocalEscapeAnalysis {
    private val DEBUG = 0

    private inline fun DEBUG_OUTPUT(severity: Int, block: () -> Unit) {
        if (DEBUG > severity) block()
    }

    private enum class EscapeState {
        GLOBAL_ESCAPE,       // escape through global reference
        ARG_ESCAPE,          // escapes through function arguments, return or throw
        NO_ESCAPE            // object does not escape
    }

    class FunctionAnalyzer(val function: DataFlowIR.Function, val context: Context) {
        // Escape states of nodes.
        private val nodesEscapeStates = mutableMapOf<DataFlowIR.Node, EscapeState>()
        // Connected objects map, escape state change of key node influences on all connected nodes states.
        private val connectedObjects = mutableMapOf<DataFlowIR.Node, MutableSet<DataFlowIR.Node>>()
        // Maximum size of array which is allowed to allocate on stack.
        // TODO: replace into KonanConfigKeys?
        private val stackAllocationArraySizeLimit = 64

        private var DataFlowIR.Node.escapeState: EscapeState
            set(value) {
                // Write state only if it has more limitations.
                val writeState = nodesEscapeStates[this]?.let {
                    value < it
                } ?: true
                if (writeState) {
                    nodesEscapeStates[this] = value
                }
            }
            get() = nodesEscapeStates.getOrDefault(this, EscapeState.NO_ESCAPE)

        private fun connectObjects(node: DataFlowIR.Node, connectedNode: DataFlowIR.Node) {
            connectedObjects.getOrPut(node) { mutableSetOf() }.add(connectedNode)
        }

        private fun findOutArraySize(node: DataFlowIR.Node): Int? {
            if (node is DataFlowIR.Node.SimpleConst<*>) {
                return node.value as? Int
            }
            if (node is DataFlowIR.Node.Variable) {
                // In case of several possible values, it's unknown what is used.
                // TODO: if all values are constants which are less limit?
                if (node.values.size == 1) {
                    return findOutArraySize(node.values.first().node)
                }
            }
            return null
        }

        private fun evaluateEscapeState(node: DataFlowIR.Node) {
            node.escapeState = EscapeState.NO_ESCAPE
            when (node) {
                is DataFlowIR.Node.Call -> {
                    node.arguments.forEachIndexed { index, arg ->
                        // Check information about arguments escaping.
                        val escapes = node.callee.escapes?.let {
                            it and (1 shl index) != 0
                        } ?: node.callee.irFunction?.isExternal?.let {
                            // Case of array_get function when ot returns part of memory.
                            // TODO: set some flag for safe external functions?
                            connectObjects(node, arg.node)
                            !(it || node.callee.irFunction.isBuiltInOperator)
                        } ?: true
                        arg.node.escapeState = if (escapes) EscapeState.ARG_ESCAPE else EscapeState.NO_ESCAPE
                    }

                    // Check size for array allocation.
                    if (node is DataFlowIR.Node.NewObject && node.constructedType is DataFlowIR.Type.Declared) {
                        node.constructedType.irClass?.let { irClass ->
                            // Work only with arrays which elements size is known.
                            if (irClass.isArrayWithFixedSizeItems) {
                                val sizeArgument = node.arguments.first().node
                                val arraySize = findOutArraySize(sizeArgument)
                                if (arraySize == null || arraySize > stackAllocationArraySizeLimit) {
                                    node.escapeState = EscapeState.GLOBAL_ESCAPE
                                }
                            } else {
                                node.escapeState = EscapeState.GLOBAL_ESCAPE
                            }
                        }
                    }
                }
                is DataFlowIR.Node.Singleton -> {
                    node.escapeState = EscapeState.GLOBAL_ESCAPE
                }
                is DataFlowIR.Node.FieldRead -> {
                    node.receiver?.let { obj ->
                        connectObjects(node, obj.node)
                    } ?: run { node.escapeState = EscapeState.GLOBAL_ESCAPE }
                }
                is DataFlowIR.Node.FieldWrite -> {
                    node.receiver?.let { obj ->
                        connectObjects(obj.node, node.value.node)
                    } ?: run {
                        node.escapeState = EscapeState.GLOBAL_ESCAPE
                        node.value.node.escapeState = EscapeState.GLOBAL_ESCAPE
                    }
                }
                is DataFlowIR.Node.ArrayWrite -> {
                    connectObjects(node.array.node, node.value.node)
                }
                is DataFlowIR.Node.Variable -> {
                    node.values.forEach {
                        connectObjects(node, it.node)
                    }
                }
                is DataFlowIR.Node.ArrayRead -> {
                    // If element of array escapes then array also should escape.
                    connectObjects(node, node.array.node)
                }
            }
        }

        private inner class ConnectedObjectsVisitor {
            val visitedObjects = mutableSetOf<DataFlowIR.Node>()

            fun visit(node: DataFlowIR.Node, action: (DataFlowIR.Node) -> Unit) {
                action(node)
                visitedObjects.add(node)
                connectedObjects[node]?.forEach {
                    if (!visitedObjects.contains(it)) {
                        visit(it, action)
                    }
                }
            }
        }

        private fun propagateState(state: EscapeState, visitor: ConnectedObjectsVisitor) {
            connectedObjects.filter { it.key.escapeState == state }.forEach { (key, value) ->
                value.forEach { obj ->
                    visitor.visit(obj) { it.escapeState = key.escapeState }
                }
            }
        }

        fun analyze(lifetimes: MutableMap<IrElement, Lifetime>) {
            function.body.nodes.forEach { node ->
                evaluateEscapeState(node)
            }
            function.body.returns.escapeState = EscapeState.ARG_ESCAPE
            function.body.throws.escapeState = EscapeState.ARG_ESCAPE

            // Change state of connected objects.
            val visitor = ConnectedObjectsVisitor()
            propagateState(EscapeState.GLOBAL_ESCAPE, visitor)
            propagateState(EscapeState.ARG_ESCAPE, visitor)

            nodesEscapeStates.filter {
                it.value == EscapeState.NO_ESCAPE
            }.forEach { (irNode, _) ->
                val ir = when (irNode) {
                    is DataFlowIR.Node.Call -> irNode.irCallSite
                    else -> null
                }
                ir?.let {
                    lifetimes.put(it, Lifetime.LOCAL)
                    DEBUG_OUTPUT(3) { println("${ir} does not escape") }
                }
            }
        }
    }

    fun analyze(context: Context, moduleDFG: ModuleDFG, lifetimes: MutableMap<IrElement, Lifetime>) {
        moduleDFG.functions.forEach { (name, function) ->
            DEBUG_OUTPUT(5) {
                println("===============================================")
                println("Visiting $name")
                println("DATA FLOW IR:")
                function.debugOutput()
            }

            FunctionAnalyzer(function, context).analyze(lifetimes)
        }
    }

    fun computeLifetimes(context: Context, moduleDFG: ModuleDFG, lifetimes: MutableMap<IrElement, Lifetime>) {
        DEBUG_OUTPUT(1) { println("In local EA") }
        assert(lifetimes.isEmpty())
        analyze(context, moduleDFG, lifetimes)
    }
}