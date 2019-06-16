#!/usr/bin/groovy
package io.estrado;

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

// // from https://stackoverflow.com/questions/13155127/deep-copy-map-in-groovy:
// OV does not work: I get an exception "Caused: java.io.NotSerializableException: java.io.ByteArrayOutputStream"
// @NonCPS
// def deepCopy(orig) {
//      bos = new ByteArrayOutputStream()
//      oos = new ObjectOutputStream(bos)
//      oos.writeObject(orig); oos.flush()
//      bin = new ByteArrayInputStream(bos.toByteArray())
//      ois = new ObjectInputStream(bin)
//      return ois.readObject()
// }

def setDefaults(Map args) {
    // deepCopy does not work. Therefore, the input Map will be changed by this function
    def myArgs = args
    if (args == null) {
        myArgs = [:]
    } else {
        myArgs = args
    }
    // else {
    //     myArgs = deepCopy(args)
    // }

    // DEFAULTS
    myArgs.app                     = myArgs.app != null                    ?    myArgs.app                      : [:]
    myArgs.alwaysPerformTests      = myArgs.alwaysPerformTests != null     ?    myArgs.alwaysPerformTests       : (env.getProperty('ALWAYS_PERFORM_TESTS')         != null ? (env.getProperty('ALWAYS_PERFORM_TESTS')         == "true" ? true : false) : false)
    
    myArgs.branchNameNormalized    = normalizeName(name:env.BRANCH_NAME, maxLength:30, digestLength:6)
    
    myArgs.commitTag               = getGitCommitSha(length:7)

    myArgs.debug                   = myArgs.debug != null                   ?    myArgs.debug                   : [:]
    myArgs.debug.helmStatus        = myArgs.debug.helmStatus != null        ?    myArgs.debug.helmStatus        : (env.getProperty('DEBUG_HELM_STATUS')            != null ? (env.getProperty('DEBUG_HELM_STATUS')            == "true" ? true : false) : false)
    myArgs.debug.envVars           = myArgs.debug.envVars != null           ?    myArgs.debug.envVars           : (env.getProperty('DEBUG_ENV_VARS')               != null ? (env.getProperty('DEBUG_ENV_VARS')            == "true" ? true : false) : false)
    myArgs.debugPipeline           = myArgs.debugPipeline != null           ?    myArgs.debugPipeline           : (env.getProperty('DEBUG_PIPELINE')               != null ? (env.getProperty('DEBUG_PIPELINE')               == "true" ? true : false) : false)
    
    myArgs.helmTestRetry           = myArgs.helmTestRetry != null           ?    myArgs.helmTestRetry           : (env.getProperty('HELM_TEST_RETRY')              != null ? env.getProperty('HELM_TEST_RETRY').toInteger()                        : 0)

    myArgs.sharedSelenium          = myArgs.sharedSelenium != null          ?    myArgs.sharedSelenium          : (env.getProperty('SHARED_SELENIUM')              != null ? (env.getProperty('SHARED_SELENIUM')              == "true" ? true : false) : false)
    myArgs.skipRemoveAppIfNotProd  = myArgs.skipRemoveAppIfNotProd != null  ?    myArgs.skipRemoveAppIfNotProd  : (env.getProperty('SKIP_REMOVE_APP_IF_NOT_PROD')  != null ? (env.getProperty('SKIP_REMOVE_APP_IF_NOT_PROD')  == "true" ? true : false) : false)
    myArgs.skipRemoveTestPods      = myArgs.skipRemoveTestPods != null      ?    myArgs.skipRemoveTestPods      : (env.getProperty('SKIP_REMOVE_TEST_PODS')        != null ? (env.getProperty('SKIP_REMOVE_TEST_PODS')        == "true" ? true : false) : false)
    myArgs.showHelmTestLogs        = myArgs.showHelmTestLogs != null        ?    myArgs.showHelmTestLogs        : (env.getProperty('SHOW_HELM_TEST_LOGS')          != null ? (env.getProperty('SHOW_HELM_TEST_LOGS')          == "true" ? true : false) : true)

    // set appRelease:
    myArgs.appRelease    = env.BRANCH_NAME == "prod" ? myArgs.app.name : myArgs.branchNameNormalized
    myArgs.appNamespace  = env.BRANCH_NAME == "prod" ? myArgs.app.name : myArgs.branchNameNormalized
    myArgs.skipRemoveApp = env.BRANCH_NAME == "prod" ? true                   : myArgs.skipRemoveAppIfNotProd

    // Set Selenium myArgs
    myArgs.seleniumRelease       = myArgs.sharedSelenium == true      ?    'selenium'   : (myArgs.appRelease + '-selenium')
    myArgs.seleniumNamespace     = myArgs.sharedSelenium == true      ?    'selenium'   : myArgs.appNamespace

    // set additional git envvars for image tagging
    gitEnvVars()

    // If pipeline debugging enabled
    if (myArgs.debug.envVars) {
        println "DEBUGGING of ENV VARS ENABLED"
        sh "env | sort"
    }

    myArgs.acct                  = getContainerRepoAcct(myArgs)
    myArgs.image_tags_list       = getMapValues(getContainerTags(myArgs))

    echo "myArgs.image_tags_list = ${myArgs.image_tags_list}"

    myArgs.app.programmingLanguage   = myArgs.app.programmingLanguage != null     ?    myArgs.app.programmingLanguage       : (env.getProperty('PROGRAMMING_LANGUAGE')         != null ? env.getProperty('PROGRAMMING_LANGUAGE') : "programming_language_not_found")

    switch(myArgs.app.programmingLanguage) {
        case ~/golang/:
            myArgs.unitTestCommandDefault = "go test -v -race ./..."
            myArgs.buildCommandDefault    = "make bootstrap build"
        break
        default:
            myArgs.unitTestCommandDefault = "unitTest: unsupported programmingLanguage"
            myArgs.buildCommandDefault    = "build: unsupported programmingLanguage"
    }

    myArgs.unitTestCommand   = myArgs.unitTestCommand != null     ?    myArgs.unitTestCommand       : myArgs.unitTestCommandDefault
    myArgs.buildCommand      = myArgs.buildCommand    != null     ?    myArgs.buildCommand          : myArgs.buildCommandDefault

    return myArgs
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

def helmInit() {
    //setup helm connectivity to Kubernetes API and Tiller
    println "initializing helm client"
    sh "helm init --client-only"
    println "checking client/server version"
    sh "helm version"
}

def helmDebugInContainers(appRelease, appNamespace) {
    container('helm') {
        helmStatus = pipeline.helmStatus(
            name    : appRelease
        )
    }
    container('kubectl') {
        sh "kubectl -n ${appNamespace} get all || true"
    }
}

def helmDeploy(Map args) {
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