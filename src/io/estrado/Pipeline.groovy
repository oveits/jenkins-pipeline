#!/usr/bin/groovy
package io.estrado;

def enrichConfiguration(Map configuration) {
    // DEFAULTS
    configuration.alwaysPerformTests      = configuration.alwaysPerformTests != null     ?    configuration.alwaysPerformTests       : (env.getProperty('ALWAYS_PERFORM_TESTS')         != null ? (env.getProperty('ALWAYS_PERFORM_TESTS')         == "true" ? true : false) : false)
    configuration.debugPipeline           = configuration.debugPipeline != null          ?    configuration.debugPipeline            : (env.getProperty('DEBUG_PIPELINE')               != null ? (env.getProperty('DEBUG_PIPELINE')               == "true" ? true : false) : false)
    configuration.sharedSelenium          = configuration.sharedSelenium != null         ?    configuration.sharedSelenium           : (env.getProperty('SHARED_SELENIUM')              != null ? (env.getProperty('SHARED_SELENIUM')              == "true" ? true : false) : false)
    // configuration.seleniumRelease:     will be set further below
    // configuration.seleniumNamespace:   will be set further below
    configuration.skipRemoveAppIfNotProd  = configuration.skipRemoveAppIfNotProd != null  ?    configuration.skipRemoveAppIfNotProd  : (env.getProperty('SKIP_REMOVE_APP_IF_NOT_PROD')  != null ? (env.getProperty('SKIP_REMOVE_APP_IF_NOT_PROD')  == "true" ? true : false) : false)
    configuration.skipRemoveTestPods      = configuration.skipRemoveTestPods != null      ?    configuration.skipRemoveTestPods      : (env.getProperty('SKIP_REMOVE_TEST_PODS')        != null ? (env.getProperty('SKIP_REMOVE_TEST_PODS')        == "true" ? true : false) : false)
    configuration.showHelmTestLogs        = configuration.showHelmTestLogs != null        ?    configuration.showHelmTestLogs        : (env.getProperty('SHOW_HELM_TEST_LOGS')          != null ? (env.getProperty('SHOW_HELM_TEST_LOGS')          == "true" ? true : false) : true)
    configuration.debug                   = configuration.debug != null                   ?    configuration.debug                   : [:]
    configuration.debug.helmStatus        = configuration.debug.helmStatus != null        ?    configuration.debug.helmStatus        : (env.getProperty('DEBUG_HELM_STATUS')            != null ? (env.getProperty('DEBUG_HELM_STATUS')            == "true" ? true : false) : false)
    configuration.debug.envVars           = configuration.debug.envVars != null           ?    configuration.debug.envVars           : (env.getProperty('DEBUG_ENV_VARS')               != null ? (env.getProperty('DEBUG_ENV_VARS')            == "true" ? true : false) : false)
    configuration.helmTestRetry           = configuration.helmTestRetry != null           ?    configuration.helmTestRetry           : (env.getProperty('HELM_TEST_RETRY')              != null ? env.getProperty('HELM_TEST_RETRY').toInteger()                        : 0)

    // set commitTag
    String gitRevParseHead = sh script: 'git rev-parse HEAD', returnStdout: true
    configuration.commitTag = gitRevParseHead.substring(0, 7).trim()
    echo "commitTag = ${configuration.commitTag}"      

    // set branchNameNormalized:
    // - replaces '/' by '-' 
    // - shortens branch name, if needed. In that case, add a 6 Byte hash
    configuration.branchNameNormalized = env.BRANCH_NAME.toLowerCase().replaceAll('/','-')
    if (configuration.branchNameNormalized.length() > 30) {
        String digest = sh script: "echo ${env.BRANCH_NAME} | md5sum | cut -c1-6 | tr -d '\\n' | tr -d '\\r'", returnStdout: true
        configuration.branchNameNormalized = configuration.branchNameNormalized.take(24) + '-' + digest
        if (configuration.pipeline.debug) {
            echo "digest = ${digest}"
        }
    }
    echo "configuration.branchNameNormalized = ${configuration.branchNameNormalized}"
    // configuration.branchNameNormalized = branchNameNormalized

    // set appRelease:
    configuration.appRelease    = env.BRANCH_NAME == "prod" ? configuration.app.name : configuration.branchNameNormalized
    configuration.appNamespace  = env.BRANCH_NAME == "prod" ? configuration.app.name : configuration.branchNameNormalized
    configuration.skipRemoveApp = env.BRANCH_NAME == "prod" ? true                   : configuration.skipRemoveAppIfNotProd

    // Set Selenium configuration
    configuration.seleniumRelease       = configuration.sharedSelenium == true      ?    'selenium'   : (configuration.appRelease + '-selenium')
    configuration.seleniumNamespace     = configuration.sharedSelenium == true      ?    'selenium'   : configuration.appNamespace

    // set additional git envvars for image tagging
    gitEnvVars()

    // If pipeline debugging enabled
    if (configuration.debug.envVars) {
        println "DEBUGGING of ENV VARS ENABLED"
        sh "env | sort"
    }

    configuration.acct                  = getContainerRepoAcct(configuration)
    configuration.image_tags_list       = getMapValues(getContainerTags(configuration))

    echo "configuration.image_tags_list = ${configuration.image_tags_list}"

}

def kubectlTest() {
    // Test that kubectl can correctly communication with the Kubernetes API
    println "checking kubectl connnectivity to the API"
    sh "kubectl get nodes"

}

def helmLint(String chart_dir) {
    // lint helm chart
    println "running helm lint ${chart_dir}"
    sh "helm lint ${chart_dir}"

}

def helmConfig() {
    //setup helm connectivity to Kubernetes API and Tiller
    println "initiliazing helm client"
    sh "helm init --client-only"
    println "checking client/server version"
    sh "helm version"
}


def helmDeploy(Map args) {
    //configure helm client and confirm tiller process is installed
    helmConfig()
    def String release_overrides = ""
    if (args.set) {
      release_overrides = getHelmReleaseOverrides(args.set)
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

        sh "helm upgrade --dry-run --install --force ${args.name} ${args.chart_dir} " + (release_overrides ? "--set ${release_overrides}" : "") + " --namespace=${namespace}"
    } else {
        println "Running deployment"

        sh "helm dependency update ${args.chart_dir}"
        sh "helm upgrade --install --force ${args.name} ${args.chart_dir} " + (release_overrides ? "--set ${release_overrides}" : "") + " --namespace=${namespace}" + " --wait"

        echo "Application ${args.name} successfully deployed. Use helm status ${args.name} to check"
    }
}

def helmDelete(Map args) {
        println "Running helm delete ${args.name}"

        sh "helm delete ${args.name} --purge"
}

def helmTest(Map args) {
    println "Running Helm test"

    sh "helm test ${args.name} --cleanup"
}

def helmStatus(Map args) {
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

def gitEnvVars() {
    // create git envvars
    println "Setting envvars to tag container"

    sh 'git rev-parse HEAD > git_commit_id.txt'
    try {
        env.GIT_COMMIT_ID = readFile('git_commit_id.txt').trim()
        env.GIT_SHA = env.GIT_COMMIT_ID.substring(0, 7)
    } catch (e) {
        error "${e}"
    }
    println "env.GIT_COMMIT_ID ==> ${env.GIT_COMMIT_ID}"

    sh 'git config --get remote.origin.url> git_remote_origin_url.txt'
    try {
        env.GIT_REMOTE_URL = readFile('git_remote_origin_url.txt').trim()
    } catch (e) {
        error "${e}"
    }
    println "env.GIT_REMOTE_URL ==> ${env.GIT_REMOTE_URL}"
}


def containerBuildPub(Map args) {

    println "Running Docker build/publish: ${args.host}/${args.acct}/${args.repo}:${args.tags}"

    // perform docker login to container registry as the docker-pipeline-plugin doesn't work with the next auth json format
    withCredentials([[$class          : 'UsernamePasswordMultiBinding', credentialsId: args.auth_id,
            usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD']]) {
      sh "echo ${env.PASSWORD} | docker login -u ${env.USERNAME} --password-stdin ${args.host}"
    }

    def img = docker.image("${args.host}/${args.acct}/${args.repo}")

    sh "docker build --build-arg VCS_REF=${env.GIT_SHA} --build-arg BUILD_DATE=`date -u +'%Y-%m-%dT%H:%M:%SZ'` -t ${args.host}/${args.acct}/${args.repo} ${args.dockerfile}"

    for (int i = 0; i < args.tags.size(); i++) {
        img.push(args.tags.get(i))
    }

    return img.id
}

def getContainerTags(config, Map tags = [:]) {

    println "getting list of tags for container"
    def String commit_tag
    def String version_tag

    try {
        // if PR branch tag with only branch name
        if (env.BRANCH_NAME.contains('PR')) {
            commit_tag = env.BRANCH_NAME
            tags << ['commit': commit_tag]
            return tags
        }
    } catch (Exception e) {
        println "WARNING: commit unavailable from env. ${e}"
    }

    // commit tag
    try {
        // if branch available, use as prefix, otherwise only commit hash
        if (env.BRANCH_NAME) {
            commit_tag = env.BRANCH_NAME.replaceAll('/','-') + '-' + env.GIT_COMMIT_ID.substring(0, 7)
        } else {
            commit_tag = env.GIT_COMMIT_ID.substring(0, 7)
        }
        tags << ['commit': commit_tag]
    } catch (Exception e) {
        println "WARNING: commit unavailable from env. ${e}"
    }

    // master tag
    try {
        if (env.BRANCH_NAME == 'master') {
            tags << ['master': 'latest']
        }
    } catch (Exception e) {
        println "WARNING: branch unavailable from env. ${e}"
    }

    // build tag only if none of the above are available
    if (!tags) {
        try {
            tags << ['build': env.BUILD_TAG]
        } catch (Exception e) {
            println "WARNING: build tag unavailable from config.project. ${e}"
        }
    }

    return tags
}

def getContainerRepoAcct(config) {

    println "setting container registry creds according to Jenkinsfile.json"
    def String acct

    if (env.BRANCH_NAME == 'master') {
        acct = config.container_repo.master_acct
    } else {
        acct = config.container_repo.alt_acct
    }

    return acct
}

@NonCPS
def getMapValues(Map map=[:]) {
    // jenkins and workflow restriction force this function instead of map.values(): https://issues.jenkins-ci.org/browse/JENKINS-27421
    def entries = []
    def map_values = []

    entries.addAll(map.entrySet())

    for (int i=0; i < entries.size(); i++){
        String value =  entries.get(i).value
        map_values.add(value)
    }

    return map_values
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

def String getDomainName(String url) throws URISyntaxException {
    URI uri = new URI(url);
    String domain = uri.getHost();
    return domain.startsWith("www.") ? domain.substring(4) : domain;
}

def String getSubDomainName(String domain) {
    return domain.substring(domain.indexOf('.') + 1);
}

// Used to get the subdomain Jenkins is hosted on for new ingress resources.
def String getSubDomainNameFromURL(String url) {
    return getSubDomainName(getDomainName(url));
}

def setConfiguration (String configuration, String environment, String dValue ){
  if(configuration != null){
    return configuration
  }

  if(environment != null){
    return environment
  }

  return dValue
}