#!/usr/bin/groovy
package com.vocon-it.pipeline;

def buildAndPublish(Map args) {

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