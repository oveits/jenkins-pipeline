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

def deleteObsoletePods(Map args = null) {
    if(args?.namespace == null) {
        echo "ERROR: deleteObsoletePods(): required namespace parameter missing"
        return false
    }
    String namespace = args.namespace
    sh """
    PODS=\$(kubectl -n ${namespace} get pods | grep 'Completed\\|Error' | awk '{print \$1}')
    if [ "\$PODS" != "" ]; then
        echo \$PODS | xargs -n 1 kubectl -n ${namespace} delete pod
    else
        echo "no completed PODs found; continuing"
    fi
    """
    return true
}
