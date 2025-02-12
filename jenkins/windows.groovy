@NonCPS
def parseJson(def text) {
  new groovy.json.JsonSlurperClassic().parseText(text)
}

node('windows') {
  def production = params.PRODUCTION
  def custom = params.CUSTOM
  def NODE = tool name: 'node-v16.17.1', type: 'nodejs'

  def jenkinsbot_secret = ''
  withCredentials([string(credentialsId: "${params.JENKINSBOT_SECRET}", variable: 'JENKINSBOT_SECRET')]) {
    jenkinsbot_secret = env.JENKINSBOT_SECRET
  }

  if (!production && !custom) {
    env.APP_ENV = 'internal'
  }

  stage('Checkout & Clean') {
    git branch: "${GIT_BRANCH}", url: 'https://github.com/wireapp/wire-desktop.git'
    bat returnStatus: true, script: 'rmdir /s /q "node_modules"'
  }

  def wireJson = readFile('electron/wire.json')
  def packageJson = readFile('package.json')
  def (major, minor) = parseJson(wireJson).version.tokenize('.')
  def version = "${major}.${minor}.${env.BUILD_NUMBER}"
  def electronVersion = parseJson(packageJson).devDependencies.electron
  currentBuild.displayName = version

  stage('Probe signtool') {
    try {
      timeout(activity: true, time: 30, unit: 'SECONDS') {
        bat '"C:\\Program Files (x86)\\Windows Kits\\10\\bin\\x86\\signtool.exe" sign /t http://timestamp.digicert.com /a C:\\Users\\jenkins\\Downloads\\Git-2.21.0-64-bit.exe'
      }
    } catch(e) {
      currentBuild.result = 'FAILED'
      wireSend secret: "${jenkinsbot_secret}", message: "🏞 **${JOB_NAME} ${version} signing failed**\n\n⚠️ Please **manually** enter the signing key on the machine or use the unlock dongle job!"
      throw e
    }
  }

  stage('Build') {
    try {
      withEnv(["PATH+NODE=${NODE}", 'npm_config_target_arch=x64']) {
        bat 'node -v'
        bat 'npm -v'
        bat 'npm install -g yarn'
        bat 'yarn'
        bat 'yarn build:win'
      }
    } catch(e) {
      currentBuild.result = 'FAILED'
      wireSend secret: "${jenkinsbot_secret}", message: "🏞 **${JOB_NAME} ${version} build failed**\n${BUILD_URL}"
      throw e
    }
  }

  stage('Build installer') {
    try {
      withEnv(["PATH+NODE=${NODE}",'npm_config_target_arch=x64']) {
        bat 'yarn build:win:installer'
      }
    } catch(e) {
      currentBuild.result = 'FAILED'
      wireSend secret: "${jenkinsbot_secret}", message: "🏞 **${JOB_NAME} ${version} building installer failed**\n${BUILD_URL}"
      throw e
    }
  }

  stage('Sign installer') {
    try {
      bat 'for %%f in ("wrap\\dist\\*-Setup.exe") do "C:\\Program Files (x86)\\Windows Kits\\10\\bin\\x86\\signtool.exe" sign /t http://timestamp.digicert.com /fd SHA256 /a "%%f"'
    } catch(e) {
      currentBuild.result = 'FAILED'
      wireSend secret: "${jenkinsbot_secret}", message: "🏞 **${JOB_NAME} ${version} signing installer failed**\n${BUILD_URL}"
      throw e
    }
  }

  stage('Archive build artifacts') {
    archiveArtifacts 'wrap\\dist\\**'
  }

  stage('Print hash') {
    try {
      if (production) {
        bat 'certUtil -hashfile "wrap\\dist\\Wire-Setup.exe" SHA256'
      }
    } catch(e) {
      currentBuild.result = 'FAILED'
      wireSend secret: "${jenkinsbot_secret}", message: "🏞 **${JOB_NAME} ${version} printing hash failed**\n${BUILD_URL}"
      throw e
    }
  }

  stage('Trigger smoke tests') {
    if (production) {
      try {
        build job: 'Wrapper_Windows_Smoke_Tests', parameters: [run(description: '', name: 'WRAPPER_BUILD', runId: "Wrapper_Windows_Production#${BUILD_ID}"), string(name: 'WEBAPP_ENV', value: 'https://wire-webapp-master.zinfra.io/')], wait: false
      } catch(e) {
        wireSend secret: "${jenkinsbot_secret}", message: "🏞 **${JOB_NAME} Unable to trigger smoke tests for ${version}**\n${BUILD_URL}"
        print e
      }
    }
  }

  wireSend secret: "${jenkinsbot_secret}", message: "🏞 **New build of ${JOB_NAME} ${version}**\n- Download: [Jenkins](${BUILD_URL})\n- Electron version: ${electronVersion}\n- Branch: [${GIT_BRANCH}](https://github.com/wireapp/wire-desktop/commits/${GIT_BRANCH})"
}
