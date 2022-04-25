/*
 * Copyright 2022 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.graphql.dgs.internal

import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.future.asCompletableFuture
import org.springframework.core.BridgeMethodResolver
import org.springframework.core.KotlinDetector
import org.springframework.core.MethodParameter
import org.springframework.core.ParameterNameDiscoverer
import org.springframework.core.annotation.SynthesizingMethodParameter
import org.springframework.util.ReflectionUtils
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.CompletableFuture
import kotlin.reflect.full.callSuspend
import kotlin.reflect.jvm.kotlinFunction

class DataFetcherInvoker internal constructor(
    private val dgsComponent: Any,
    method: Method,
    private val resolvers: ArgumentResolverComposite,
    parameterNameDiscoverer: ParameterNameDiscoverer
) : DataFetcher<Any?> {

    private val bridgedMethod: Method = BridgeMethodResolver.findBridgedMethod(method)
    private val isSuspending: Boolean = KotlinDetector.isSuspendingFunction(bridgedMethod)

    private val methodParameters: List<MethodParameter> = bridgedMethod.parameters.map { parameter ->
        val methodParameter = SynthesizingMethodParameter.forParameter(parameter)
        methodParameter.initParameterNameDiscovery(parameterNameDiscoverer)
        methodParameter
    }

    init {
        ReflectionUtils.makeAccessible(bridgedMethod)
    }

    override fun get(environment: DataFetchingEnvironment): Any? {
        if (methodParameters.isEmpty()) {
            return ReflectionUtils.invokeMethod(bridgedMethod, dgsComponent)
        }

        val args = arrayOfNulls<Any?>(methodParameters.size)

        for ((idx, parameter) in methodParameters.withIndex()) {
            if (!resolvers.supportsParameter(parameter)) {
                throw IllegalStateException(formatArgumentError(parameter, "No suitable resolver"))
            }
            args[idx] = resolvers.resolveArgument(parameter, environment)
        }

        if (isSuspending) {
            return invokeSuspending(args)
        }
        return ReflectionUtils.invokeMethod(bridgedMethod, dgsComponent, *args)
    }

    private fun invokeSuspending(args: Array<Any?>): CompletableFuture<Any?> {
        val kotlinFunction = bridgedMethod.kotlinFunction
            ?: throw IllegalStateException("Expected a Kotlin suspend function")

        val deferred = CoroutineScope(Dispatchers.Unconfined).async {
            try {
                kotlinFunction.callSuspend(dgsComponent, *args.copyOfRange(0, args.size - 1))
            } catch (exception: InvocationTargetException) {
                throw exception.cause ?: exception
            }
        }
        return deferred.asCompletableFuture()
    }

    private fun formatArgumentError(param: MethodParameter, message: String): String {
        return "Could not resolve parameter [${param.parameterIndex}] in " +
            param.executable.toGenericString() + if (message.isNotEmpty()) ": $message" else ""
    }
}
