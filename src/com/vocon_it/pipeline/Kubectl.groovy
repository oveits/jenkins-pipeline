#!/usr/bin/groovy
package com.vocon_it.pipeline;

def getNodes() {
    // Test that kubectl can correctly communication with the Kubernetes API
    println "checking kubectl connnectivity to the API"
    sh "kubectl get nodes"

}

def getAll(String namespace, Boolean ignoreErrors) {
    if(ignoreErrors || ignoreErrors == null) {
        sh "kubectl -n ${namespace} get all || true"
    } else {
        sh "kubectl -n ${namespace} get all"
    }  
}
