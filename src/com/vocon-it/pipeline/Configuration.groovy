#!/usr/bin/groovy
package com.vocon-it.pipeline;

def normalizeName(Map args) {
    // normalizeName: 
    // - replace '/' by '-', 
    // - shorten, if longer maxLength (default 30)
    //   - in that case, replace tail by a digest
    // @args
    // - String name
    // - Integer maxLength (default 30)
    // - Integer digestLength (default 6)
    if(args == null || args.name == null || args.name == "") {
        error "normalizeBranch(name:null) called?"
    }
    if(args.maxLength == null) {
        args.maxLength = 30
    }
    if(args.digestLength == null) {
        args.digestLength = 6
    }
    String normalizedBranch = args.name.toLowerCase().replaceAll('/','-')
    if (normalizedBranch.length() > args.maxLength) {
        String digest = sh script: "echo ${args.name} | md5sum | cut -c1-${args.digestLength} | tr -d '\\n' | tr -d '\\r'", returnStdout: true
        normalizedBranch = normalizedBranch.take(args.maxLength - args.digestLength - 1) + '-' + digest
        if (configuration && configuration.pipeline && configuration.pipeline.debug) {
            echo "digest = ${digest}"
        }
    }
    return normalizedBranch
}

def getGitCommitSha(Map args) {
    if (args == null || args.length == null) {
        args.length = 7
    }
    String gitRevParseHead = sh script: 'git rev-parse HEAD', returnStdout: true
    if(args.length && args.length > 0 && args.length < gitRevParseHead.length()) {
        gitRevParseHead = gitRevParseHead.substring(0, args.length).trim()
    } else {
        gitRevParseHead = gitRevParseHead.trim()
    }
    return gitRevParseHead
}

def setDefaults(Map configuration) {
    // DEFAULTS
    configuration.app                     = configuration.app != null                    ?    configuration.app                      : [:]
    configuration.alwaysPerformTests      = configuration.alwaysPerformTests != null     ?    configuration.alwaysPerformTests       : (env.getProperty('ALWAYS_PERFORM_TESTS')         != null ? (env.getProperty('ALWAYS_PERFORM_TESTS')         == "true" ? true : false) : false)
    
    configuration.branchNameNormalized    = normalizeName(name:env.BRANCH_NAME, maxLength:30, digestLength:6)
    
    configuration.commitTag               = getGitCommitSha(length:7)

    configuration.debug                   = configuration.debug != null                   ?    configuration.debug                   : [:]
    configuration.debug.helmStatus        = configuration.debug.helmStatus != null        ?    configuration.debug.helmStatus        : (env.getProperty('DEBUG_HELM_STATUS')            != null ? (env.getProperty('DEBUG_HELM_STATUS')            == "true" ? true : false) : false)
    configuration.debug.envVars           = configuration.debug.envVars != null           ?    configuration.debug.envVars           : (env.getProperty('DEBUG_ENV_VARS')               != null ? (env.getProperty('DEBUG_ENV_VARS')            == "true" ? true : false) : false)
    configuration.debugPipeline           = configuration.debugPipeline != null           ?    configuration.debugPipeline           : (env.getProperty('DEBUG_PIPELINE')               != null ? (env.getProperty('DEBUG_PIPELINE')               == "true" ? true : false) : false)
    
    configuration.helmTestRetry           = configuration.helmTestRetry != null           ?    configuration.helmTestRetry           : (env.getProperty('HELM_TEST_RETRY')              != null ? env.getProperty('HELM_TEST_RETRY').toInteger()                        : 0)

    configuration.sharedSelenium          = configuration.sharedSelenium != null          ?    configuration.sharedSelenium          : (env.getProperty('SHARED_SELENIUM')              != null ? (env.getProperty('SHARED_SELENIUM')              == "true" ? true : false) : false)
    configuration.skipRemoveAppIfNotProd  = configuration.skipRemoveAppIfNotProd != null  ?    configuration.skipRemoveAppIfNotProd  : (env.getProperty('SKIP_REMOVE_APP_IF_NOT_PROD')  != null ? (env.getProperty('SKIP_REMOVE_APP_IF_NOT_PROD')  == "true" ? true : false) : false)
    configuration.skipRemoveTestPods      = configuration.skipRemoveTestPods != null      ?    configuration.skipRemoveTestPods      : (env.getProperty('SKIP_REMOVE_TEST_PODS')        != null ? (env.getProperty('SKIP_REMOVE_TEST_PODS')        == "true" ? true : false) : false)
    configuration.showHelmTestLogs        = configuration.showHelmTestLogs != null        ?    configuration.showHelmTestLogs        : (env.getProperty('SHOW_HELM_TEST_LOGS')          != null ? (env.getProperty('SHOW_HELM_TEST_LOGS')          == "true" ? true : false) : true)

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

    configuration.app.programmingLanguage   = configuration.app.programmingLanguage != null     ?    configuration.app.programmingLanguage       : (env.getProperty('PROGRAMMING_LANGUAGE')         != null ? env.getProperty('PROGRAMMING_LANGUAGE') : "programming_language_not_found")

    switch(configuration.app.programmingLanguage) {
        case ~/golang/:
            configuration.unitTestCommandDefault = "go test -v -race ./..."
            configuration.buildCommandDefault    = "make bootstrap build"
        break
        default:
            configuration.unitTestCommandDefault = "unitTest: unsupported programmingLanguage"
            configuration.buildCommandDefault    = "build: unsupported programmingLanguage"
    }

    configuration.unitTestCommand   = configuration.unitTestCommand != null     ?    configuration.unitTestCommand       : configuration.unitTestCommandDefault
    configuration.buildCommand      = configuration.buildCommand    != null     ?    configuration.buildCommand          : configuration.buildCommandDefault

    return configuration
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