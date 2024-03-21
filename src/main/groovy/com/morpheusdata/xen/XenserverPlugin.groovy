/*
* Copyright 2022 the original author or authors.
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
package com.morpheusdata.xen

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.AccountCredential
import com.morpheusdata.model.Cloud
import groovy.util.logging.Slf4j

@Slf4j
class XenserverPlugin extends Plugin {

    @Override
    String getCode() {
        return 'xenserver'
    }

    @Override
    void initialize() {
        this.setName("XenServer Plugin")
        this.registerProvider(new XenserverCloudProvider(this,this.morpheus))
        this.registerProvider(new XenserverProvisionProvider(this,this.morpheus))
        this.registerProvider(new XenserverBackupProvider(this,this.morpheus))
    }

    /**
     * Called when a plugin is being removed from the plugin manager (aka Uninstalled)
     */
    @Override
    void onDestroy() {
        //nothing to do for now
    }

    MorpheusContext getMorpheusContext() {
        return morpheus
    }

    Map getAuthConfig(Cloud cloud) {
        def rtn = [:]

        if (!cloud.accountCredentialLoaded) {
            AccountCredential accountCredential
            try {
                if (!cloud.account?.id || !cloud.owner?.id) {
                    log.debug("cloud account or owner id is missing, loading cloud object")
                    // in some cases marshalling the cloud doesn't include the account and owner, in those cases
                    // we need to load the cloud to include those elements.
                    cloud = morpheus.services.cloud.get(cloud.id)
                }
                accountCredential = morpheus.services.accountCredential.loadCredentials(cloud)
            } catch (e) {
                // If there is no credential on the cloud, then this will error
            }
            cloud.accountCredentialLoaded = true
            cloud.accountCredentialData = accountCredential?.data
        }

        log.debug("AccountCredential loaded: $cloud.accountCredentialLoaded, Data: $cloud.accountCredentialData")

        def username
        if (cloud.accountCredentialData && cloud.accountCredentialData.containsKey('username')) {
            username = cloud.accountCredentialData['username']
        } else {
            username = cloud.configMap.username
        }
        def apiKey
        if (cloud.accountCredentialData && cloud.accountCredentialData.containsKey('password')) {
            apiKey = cloud.accountCredentialData['password']
        } else {
            apiKey = cloud.configMap.apiKey
        }

        rtn.doUsername = username
        rtn.doApiKey = apiKey
        return rtn
    }

    // get auth config from credential args, usually used for API request before a cloud is created
    Map getAuthConfig(Map args) {
        def rtn = [:]
        def accountCredentialData
        def username
        def apiKey

        if (args.credential && args.credential.type != 'local') {
            Map accountCredential
            try {
                accountCredential = morpheus.services.accountCredential.loadCredentialConfig(args.credential, [:])
            } catch (e) {
                // If there is no credential in the args, then this will error
            }
            log.debug("accountCredential: $accountCredential")
            accountCredentialData = accountCredential?.data
            if (accountCredentialData) {
                if (accountCredentialData.containsKey('username')) {
                    username = accountCredentialData['username']
                }
                if (accountCredentialData.containsKey('password')) {
                    apiKey = accountCredentialData['password']
                }
            }
        } else {
            log.debug("config: $args.config")
            username = args?.config?.username
            apiKey = args?.config?.apiKey
        }

        rtn.doUsername = username
        rtn.doApiKey = apiKey
        return rtn
    }
}
