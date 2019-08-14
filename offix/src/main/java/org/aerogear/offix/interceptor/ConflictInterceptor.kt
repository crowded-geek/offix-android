package org.aerogear.offix.interceptor

import android.util.Log
import com.apollographql.apollo.api.Error
import com.apollographql.apollo.api.Mutation
import com.apollographql.apollo.exception.ApolloException
import com.apollographql.apollo.interceptor.ApolloInterceptor
import com.apollographql.apollo.interceptor.ApolloInterceptorChain
import org.aerogear.offix.ConflictResolutionHandler
import org.aerogear.offix.Offline
import org.aerogear.offix.Offline.Companion.queueCallback
import org.aerogear.offix.OfflineCallback
import org.aerogear.offix.conflictedMutationClass
import org.aerogear.offix.interfaces.ConfliceResolutionInterface
import java.util.*
import java.util.concurrent.Executor

class ConflictInterceptor(private val conflictResolutionImpl: ConfliceResolutionInterface) : ApolloInterceptor {

    private val TAG = javaClass.simpleName

    /* Implemented queue using a linked list to store user callbacks.
       This is done to ensure that there is no overlapping of subsequent callbacks and every callback is associated with
       it's correct response.
     */
//    val queueCallback = LinkedList<ApolloInterceptor.CallBack>()

    override fun interceptAsync(
        request: ApolloInterceptor.InterceptorRequest,
        chain: ApolloInterceptorChain,
        dispatcher: Executor,
        callBack: ApolloInterceptor.CallBack
    ) {
        if (request.operation is Mutation) {
            Offline.queueCallback.addLast(callBack)
        }
        Log.d("$TAG 1", "$request")
        Log.d("$TAG 2", "${Offline.queueCallback.size}")

        /* Check is the network connection is there or not.
         */
        if (!Offline.isNetwork()) {
            /* Add the request to the list only when it's of type mutation.
               Responses to the requests containing query would be fetched from cache.
             */
            if (request.operation is Mutation) {
                Offline.requestList.add(request)
            }
            Log.d(TAG, "SIZE OF LIST : ${Offline.requestList.size}")
        } else {
            //Check if this is a mutation request.
            if (request.operation !is Mutation) {
                //Not a mutation. Nothing to do here - move on to the next link in the chain.
                chain.proceedAsync(request, dispatcher, callBack)
                return
            }

            /*
             Flow of code coming to this region depicts that network connection is there and
             the request is of Mutation type.
             */

            /* requestList is not empty means that it must be containing some mutation requests which were stored in it
               when the user was offline.
             */
            if (Offline.requestList.isNotEmpty()) {

                /* When user comes from offline to online, we wait for the user to perform any mutation.
                   Now along with the mutation performed by the user, we fetch the mutation requests stored in the list and
                   replicate them back to the server.
                   We are not providing UI bindings to the user currently.
                 */

                Offline.requestList.forEach {
                    chain.proceedAsync(it, dispatcher, OfflineCallback(it))
                }
                chain.proceedAsync(request, dispatcher, OffixConflictCallback(conflictResolutionImpl))
            } else {
                Log.d("$TAG 200", "--------")
                chain.proceedAsync(request, dispatcher, OffixConflictCallback(conflictResolutionImpl))
            }
        }
    }

    override fun dispose() {
        Log.v(TAG, "Dispose called")
    }

    inner class OffixConflictCallback(val conflictResolutionImpl: ConfliceResolutionInterface) :
        ApolloInterceptor.CallBack {
        private val TAG = javaClass.simpleName
        val userCallback = Offline.queueCallback.removeFirst()

        override fun onResponse(response: ApolloInterceptor.InterceptorResponse) {
            Log.d("$TAG", "${queueCallback.size}")

            /* Check if the conflict is present in the response of not using the ConflictResolutionHandler class.
             */
            if (ConflictResolutionHandler().conflictPresent(response.parsedResponse)) {

                Log.d("$TAG 100", "**********")
                /* Parse the response from the server into a Map object and extract the serverState and clientState.
                   Make an object of ServerClientData and add to the list.
                */
                val conflictInfo =
                    (((response.parsedResponse.get().errors()[0] as Error).customAttributes()["extensions"] as Map<*, *>)["exception"] as Map<*, *>)["conflictInfo"] as Map<*, *>

                val serverStateMap = conflictInfo["serverState"] as Map<String, Any>
                val clientStateMap = conflictInfo["clientState"] as Map<String, Any>

                conflictResolutionImpl.resolveConflict(serverStateMap, clientStateMap, conflictedMutationClass)
            } else {
//                userCallback.onResponse(response)
            }
        }

        override fun onFetch(sourceType: ApolloInterceptor.FetchSourceType?) {
            Log.d(TAG, "onFetch()")
        }

        override fun onCompleted() {
            Log.d(TAG, "onCompleted()")
        }

        override fun onFailure(e: ApolloException) {
            userCallback.onFailure(e)
            Log.d(TAG, "onFailure()")
        }
    }
}