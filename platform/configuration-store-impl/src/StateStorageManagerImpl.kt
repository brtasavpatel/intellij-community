/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.configurationStore

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import com.intellij.openapi.components.StateStorage.SaveSession
import com.intellij.openapi.components.StateStorageChooserEx.Resolution
import com.intellij.openapi.components.impl.stores.*
import com.intellij.openapi.util.Couple
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.PathUtilRt
import com.intellij.util.ReflectionUtil
import com.intellij.util.SmartList
import com.intellij.util.containers.ContainerUtil
import gnu.trove.THashMap
import org.jdom.Element
import org.picocontainer.MutablePicoContainer
import org.picocontainer.PicoContainer
import java.io.File
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import java.util.regex.Pattern
import kotlin.concurrent.withLock
import kotlin.reflect.jvm.java

/**
 * If parentDisposable not specified, storage will not add file tracker (see VirtualFileTracker)
 */
open class StateStorageManagerImpl(private val pathMacroSubstitutor: TrackingPathMacroSubstitutor,
                                   protected val rootTagName: String,
                                   private val picoContainer: PicoContainer,
                                   private val parentDisposable: Disposable? = null) : StateStorageManager {
  private val macros = LinkedHashMap<String, String>()
  private val storageLock = ReentrantLock()
  private val storages = THashMap<String, StateStorage>()

  private var streamProvider: StreamProvider? = null

  protected open val isUseXmlProlog: Boolean
    get() = true

  companion object {
    private val MACRO_PATTERN = Pattern.compile("(\\$[^\\$]*\\$)")
  }

  override final fun getStreamProvider() = streamProvider

  override final fun setStreamProvider(value: StreamProvider?) {
    streamProvider = value
  }

  override final fun getMacroSubstitutor() = pathMacroSubstitutor

  synchronized override fun addMacro(macro: String, expansion: String) {
    assert(!macro.isEmpty())
    macros.put(macro, expansion)
  }

  override final fun getStateStorage(storageSpec: Storage) = getOrCreateStorage(storageSpec.file, storageSpec.roamingType, storageSpec.storageClass.java as Class<out StateStorage>, storageSpec.stateSplitter.java)

  override final fun getStateStorage(fileSpec: String, roamingType: RoamingType) = getOrCreateStorage(fileSpec, roamingType)

  fun getOrCreateStorage(fileSpec: String, roamingType: RoamingType, storageClass: Class<out StateStorage> = javaClass<StateStorage>(), SuppressWarnings("deprecation") stateSplitter: Class<out StateSplitter> = javaClass<StateSplitterEx>()): StateStorage {
    val key = if (storageClass == javaClass<StateStorage>()) fileSpec else storageClass.getName()
    storageLock.withLock {
      var stateStorage = storages.get(key)
      if (stateStorage == null) {
        stateStorage = createStateStorage(storageClass, fileSpec, roamingType, stateSplitter)
        storages.put(key, stateStorage)
      }
      return stateStorage
    }
  }

  override final fun getCachedFileStateStorages(changed: Collection<String>, deleted: Collection<String>) = storageLock.withLock { Couple.of(getCachedFileStorages(changed), getCachedFileStorages(deleted)) }

  fun getCachedFileStorages(fileSpecs: Collection<String>): Collection<FileBasedStorage> {
    if (fileSpecs.isEmpty()) {
      return emptyList()
    }

    var result: MutableList<FileBasedStorage>? = null
    for (fileSpec in fileSpecs) {
      val storage = storages.get(fileSpec)
      if (storage is FileBasedStorage) {
        if (result == null) {
          result = SmartList<FileBasedStorage>()
        }
        result.add(storage)
      }
    }
    return result ?: emptyList<FileBasedStorage>()
  }

  override final fun getStorageFileNames() = storageLock.withLock { storages.keySet() }

  // overridden in upsource
  protected open fun createStateStorage(storageClass: Class<out StateStorage>, fileSpec: String, roamingType: RoamingType, SuppressWarnings("deprecation") stateSplitter: Class<out StateSplitter>): StateStorage {
    if (storageClass != javaClass<StateStorage>()) {
      val key = UUID.randomUUID().toString()
      (picoContainer as MutablePicoContainer).registerComponentImplementation(key, storageClass)
      return picoContainer.getComponentInstance(key) as StateStorage
    }

    val filePath = expandMacros(fileSpec)
    val file = File(filePath)

    //noinspection deprecation
    if (stateSplitter != javaClass<StateSplitter>() && stateSplitter != javaClass<StateSplitterEx>()) {
      return DirectoryBasedStorage(pathMacroSubstitutor, file, ReflectionUtil.newInstance(stateSplitter), parentDisposable, createStorageTopicListener())
    }

    if (!ApplicationManager.getApplication().isHeadlessEnvironment() && PathUtilRt.getFileName(filePath).lastIndexOf('.') < 0) {
      throw IllegalArgumentException("Extension is missing for storage file: " + filePath)
    }

    val effectiveRoamingType = if (roamingType == RoamingType.PER_USER && fileSpec == StoragePathMacros.WORKSPACE_FILE) RoamingType.DISABLED else roamingType
    beforeFileBasedStorageCreate()
    return object : FileBasedStorage(file, fileSpec, effectiveRoamingType, getMacroSubstitutor(fileSpec), rootTagName, parentDisposable, createStorageTopicListener(), streamProvider) {
      override fun createStorageData() = createStorageData(myFileSpec, getFilePath())

      override fun isUseXmlProlog() = isUseXmlProlog
    }
  }

  override final fun clearStateStorage(file: String) {
    storageLock.withLock { storages.remove(file) }
  }

  protected open fun createStorageTopicListener(): StateStorage.Listener? = null

  protected open fun beforeFileBasedStorageCreate() {
  }

  protected open fun getMacroSubstitutor(fileSpec: String): TrackingPathMacroSubstitutor? = pathMacroSubstitutor

  protected open fun createStorageData(fileSpec: String, filePath: String): StorageData = StorageData(rootTagName)

  synchronized override final fun expandMacros(file: String): String {
    val matcher = MACRO_PATTERN.matcher(file)
    while (matcher.find()) {
      val m = matcher.group(1)
      if (!macros.containsKey(m)) {
        throw IllegalArgumentException("Unknown macro: $m in storage file spec: $file")
      }
    }

    var expanded = file
    for (entry in macros.entrySet()) {
      expanded = StringUtil.replace(expanded, entry.getKey(), entry.getValue())
    }
    return expanded
  }

  override final fun collapseMacros(path: String): String {
    var result = path
    for (entry in macros.entrySet()) {
      result = StringUtil.replace(result, entry.getValue(), entry.getKey())
    }
    return result
  }

  override fun startExternalization() = StateStorageManagerExternalizationSession(this)

  open class StateStorageManagerExternalizationSession(protected val storageManager: StateStorageManagerImpl) : StateStorageManager.ExternalizationSession {
    private val mySessions = LinkedHashMap<StateStorage, StateStorage.ExternalizationSession>()

    override fun setState(storageSpecs: Array<Storage>, component: Any, componentName: String, state: Any) {
      val stateStorageChooser = component as? StateStorageChooserEx
      for (storageSpec in storageSpecs) {
        val resolution = if (stateStorageChooser == null) Resolution.DO else stateStorageChooser.getResolution(storageSpec, StateStorageOperation.WRITE)
        if (resolution == Resolution.SKIP) {
          continue
        }

        getExternalizationSession(storageManager.getStateStorage(storageSpec))?.setState(component, componentName, if (storageSpec.deprecated || resolution === Resolution.CLEAR) Element("empty") else state, storageSpec)
      }
    }

    override fun setStateInOldStorage(component: Any, componentName: String, state: Any) {
      val stateStorage = storageManager.getOldStorage(component, componentName, StateStorageOperation.WRITE)
      if (stateStorage != null) {
        getExternalizationSession(stateStorage)?.setState(component, componentName, state, null)
      }
    }

    protected fun getExternalizationSession(stateStorage: StateStorage): StateStorage.ExternalizationSession? {
      var session: StateStorage.ExternalizationSession? = mySessions.get(stateStorage)
      if (session == null) {
        session = stateStorage.startExternalization()
        if (session != null) {
          mySessions.put(stateStorage, session)
        }
      }
      return session
    }

    override fun createSaveSessions(): List<SaveSession> {
      if (mySessions.isEmpty()) {
        return emptyList()
      }

      var saveSessions: MutableList<SaveSession>? = null
      val externalizationSessions = mySessions.values()
      for (session in externalizationSessions) {
        val saveSession = session.createSaveSession()
        if (saveSession != null) {
          if (saveSessions == null) {
            if (externalizationSessions.size() == 1) {
              return listOf(saveSession)
            }
            saveSessions = SmartList<SaveSession>()
          }
          saveSessions.add(saveSession)
        }
      }
      return ContainerUtil.notNullize(saveSessions)
    }
  }

  override fun getOldStorage(component: Any, componentName: String, operation: StateStorageOperation): StateStorage? {
    val oldStorageSpec = getOldStorageSpec(component, componentName, operation)
    @suppress("DEPRECATED_SYMBOL_WITH_MESSAGE")
    return if (oldStorageSpec == null) null else getStateStorage(oldStorageSpec, if (component is com.intellij.openapi.util.RoamingTypeDisabled) RoamingType.DISABLED else RoamingType.PER_USER)
  }

  protected open fun getOldStorageSpec(component: Any, componentName: String, operation: StateStorageOperation): String? = null
}
