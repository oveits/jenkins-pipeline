#!/usr/bin/groovy
package com.vocon-it.pipeline;

def lint(String chart_dir) {
    // lint helm chart
    println "running helm lint ${chart_dir}"
    sh "helm lint ${chart_dir}"

}

def init() {
    //setup helm connectivity to Kubernetes API and Tiller
    println "initializing helm client"
    sh "helm init --client-only"
    println "checking client/server version"
    sh "helm version"
}

def debugInContainers(appRelease, appNamespace) {
    container('helm') {
        helmStatus = pipeline.helmStatus(
            name    : appRelease
        )
    }
    container('kubectl') {
        sh "kubectl -n ${appNamespace} get all || true"
    }
}

def deploy(Map args) {
    //configure helm client and confirm tiller process is installed
    helmInit()
    def String release_overrides = ""
    if (args.set) {
      release_overrides = getHelmReleaseOverrides(args.set)
    }

    // we had a problem with commit.sha values that have no alphabetic character (e.g. commit.sha=8880525 was translated to 8.880525e+06) 
    // this is fixed in https://github.com/helm/helm/issues/1707 by introducing the --set-string option.
    def String release_overrides_string = ""
    if (args.set_string) {
      release_overrides_string = getHelmReleaseOverrides(args.set_string)
    }

    def String namespace

    // If namespace isn't parsed into the function set the namespace to the name
    if (args.namespace == null) {
        namespace = args.name
    } else {
        namespace = args.namespace
    }

    if (args.dry_run) {
        println "Running dry-run deployment"

        sh "helm upgrade --dry-run --install --force ${args.name} ${args.chart_dir}" + (release_overrides ? " --set ${release_overrides}" : "") + (release_overrides_string ? " --set-string ${release_overrides_string}" : "") + " --namespace=${namespace}"
    } else {
        println "Running deployment"

        sh "helm dependency update ${args.chart_dir}"
        sh "helm upgrade --install --force ${args.name} ${args.chart_dir}" + (release_overrides ? " --set ${release_overrides}" : "") + (release_overrides_string ? " --set-string ${release_overrides_string}" : "") + " --namespace=${namespace}" + " --wait"

        echo "Application ${args.name} successfully deployed. Use helm status ${args.name} to check"
    }
}

def delete(Map args) {
        println "Running helm delete ${args.name}"

        sh "helm delete ${args.name} --purge"
}

def test(Map args) {
    println "Running Helm test"

    sh "helm test ${args.name} --cleanup"
}

def status(Map args) {
    // get helm status
    def helmStatusText = sh script: "helm status ${args.name} -o json || true", returnStdout: true
    echo helmStatusText

    if(helmStatusText != null && helmStatusText != "") {
        def helmStatus = readJSON text: helmStatusText
        return helmStatus
    }
    // else
    return null
}

@NonCPS
def getHelmReleaseOverrides(Map map=[:]) {
    // jenkins and workflow restriction force this function instead of map.each(): https://issues.jenkins-ci.org/browse/JENKINS-27421
    def options = ""
    map.each { key, value ->
        options += "$key=$value,"
    }

    return options
}
