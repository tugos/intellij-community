// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.credentialStore

import com.google.common.cache.CacheBuilder
import com.intellij.notification.NotificationDisplayType
import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationType
import com.intellij.notification.SingletonNotificationManager
import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.diagnostic.runAndLogException
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.SystemProperties
import com.intellij.util.concurrency.QueueProcessor
import java.util.concurrent.TimeUnit

private val nullCredentials = Credentials("\u0000", OneTimeString("\u0000"))

internal val NOTIFICATION_MANAGER by lazy {
  // we use name "Password Safe" instead of "Credentials Store" because it was named so previously (and no much sense to rename it)
  SingletonNotificationManager(NotificationGroup("Password Safe", NotificationDisplayType.STICKY_BALLOON, true), NotificationType.ERROR)
}

private class CredentialStoreWrapper(private val store: CredentialStore) : CredentialStore {
  private val fallbackStore = lazy { createInMemoryKeePassCredentialStore() }

  private val queueProcessor = QueueProcessor<() -> Unit> { it() }

  private val postponedCredentials = createInMemoryKeePassCredentialStore()
  private val deniedItems = CacheBuilder.newBuilder().expireAfterAccess(1, TimeUnit.MINUTES).build<CredentialAttributes, Boolean>()

  override fun get(attributes: CredentialAttributes): Credentials? {
    postponedCredentials.get(attributes)?.let {
      return if (it === nullCredentials) null else it
    }

    if (deniedItems.getIfPresent(attributes) != null) {
      LOG.warn("User denied access to $attributes")
      return null
    }

    var store = if (fallbackStore.isInitialized()) fallbackStore.value else store
    try {
      val value = store.get(attributes)
      if (value === ACCESS_TO_KEY_CHAIN_DENIED) {
        deniedItems.put(attributes, true)
      }
      return value
    }
    catch (e: UnsatisfiedLinkError) {
      store = fallbackStore.value
      notifyUnsatisfiedLinkError(e)
      return store.get(attributes)
    }
    catch (e: Throwable) {
      LOG.error(e)
      return null
    }
  }

  override fun set(attributes: CredentialAttributes, credentials: Credentials?) {
    fun doSave() {
      var store = if (fallbackStore.isInitialized()) fallbackStore.value else store
      try {
        store.set(attributes, credentials)
      }
      catch (e: UnsatisfiedLinkError) {
        store = fallbackStore.value
        notifyUnsatisfiedLinkError(e)
        store.set(attributes, credentials)
      }
      catch (e: Throwable) {
        LOG.error(e)
      }

      postponedCredentials.set(attributes, null)
    }

    LOG.runAndLogException {
      if (fallbackStore.isInitialized()) {
        fallbackStore.value.set(attributes, credentials)
      }
      else {
        postponedCredentials.set(attributes, credentials ?: nullCredentials)
        queueProcessor.add { doSave() }
      }
    }
  }
}

private fun notifyUnsatisfiedLinkError(e: UnsatisfiedLinkError) {
  LOG.error(e)
  var message = "Credentials are remembered until ${ApplicationNamesInfo.getInstance().fullProductName} is closed."
  if (SystemInfo.isLinux) {
    message += "\nPlease install required package libsecret-1-0: sudo apt-get install libsecret-1-0 gnome-keyring"
  }
  NOTIFICATION_MANAGER.notify("Cannot Access Native Keychain", message)
}

private class MacOsCredentialStoreFactory : CredentialStoreFactory {
  override fun create(): CredentialStore? {
    if (isMacOsCredentialStoreSupported && SystemProperties.getBooleanProperty("use.mac.keychain", true)) {
      return CredentialStoreWrapper(KeyChainCredentialStore())
    }
    return null
  }
}

private class LinuxSecretCredentialStoreFactory : CredentialStoreFactory {
  override fun create(): CredentialStore? {
    if (SystemInfo.isLinux && SystemProperties.getBooleanProperty("use.linux.keychain", true)) {
      return CredentialStoreWrapper(SecretCredentialStore("com.intellij.credentialStore.Credential"))
    }
    return null
  }
}