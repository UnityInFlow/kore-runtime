package dev.unityinflow.kore.skills

import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File
import java.net.URL
import java.util.jar.JarFile

/**
 * Loads [SkillYamlDef] skill definitions from two sources (D-06):
 *  1. Classpath: every `META-INF/kore/skills/` resource directory (JAR or file).
 *  2. Filesystem: a user-configurable directory (default `./kore-skills`).
 *
 * Merge rule (D-07): when a filesystem skill has the same [SkillYamlDef.name] as
 * a classpath skill, the filesystem version wins. This lets users override
 * bundled skills without rebuilding the JAR.
 *
 * Malformed YAML files are logged to stderr and skipped — a single broken
 * skill never fails the whole loader (T-03-01 / Pitfall 5).
 */
class SkillLoader(
    private val skillsDirectory: String = "./kore-skills",
) {
    private val mapper: YAMLMapper =
        YAMLMapper().apply {
            registerModule(kotlinModule())
        }

    /**
     * Load all skills from classpath + filesystem, applying the D-07
     * filesystem-wins-on-name-collision rule.
     */
    fun loadAll(): List<SkillYamlDef> {
        val classpathSkills = loadFromClasspath()
        val filesystemSkills = loadFromFilesystem()
        val merged: MutableMap<String, SkillYamlDef> =
            classpathSkills.associateBy { it.name }.toMutableMap()
        filesystemSkills.forEach { merged[it.name] = it }
        return merged.values.toList()
    }

    private fun loadFromClasspath(): List<SkillYamlDef> {
        val resourceBase = "META-INF/kore/skills/"
        val classLoader =
            Thread.currentThread().contextClassLoader
                ?: javaClass.classLoader
                ?: return emptyList()

        val urls: List<URL> =
            try {
                classLoader.getResources(resourceBase).toList()
            } catch (ex: Exception) {
                System.err.println("kore-skills: classpath enumeration failed: ${ex.message}")
                return emptyList()
            }

        val results = mutableListOf<SkillYamlDef>()
        urls.forEach { url ->
            when (url.protocol) {
                "file" -> results.addAll(loadYamlsFromFileUrl(url))
                "jar" -> results.addAll(loadYamlsFromJarUrl(url, resourceBase))
                else ->
                    System.err.println(
                        "kore-skills: unsupported classpath resource protocol '${url.protocol}': $url",
                    )
            }
        }
        return results
    }

    private fun loadYamlsFromFileUrl(url: URL): List<SkillYamlDef> {
        val dir = File(url.toURI())
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return dir
            .listFiles { file -> file.isFile && (file.extension == "yaml" || file.extension == "yml") }
            ?.mapNotNull { parseSkillFileSafely(it) }
            ?: emptyList()
    }

    private fun loadYamlsFromJarUrl(
        url: URL,
        resourceBase: String,
    ): List<SkillYamlDef> {
        // JAR URL format: jar:file:/path/to.jar!/META-INF/kore/skills/
        val path = url.path
        val bangIdx = path.indexOf("!/")
        if (bangIdx < 0) return emptyList()
        val jarPath = path.substring("file:".length, bangIdx)
        val jar =
            try {
                JarFile(File(jarPath))
            } catch (ex: Exception) {
                System.err.println("kore-skills: cannot open jar $jarPath: ${ex.message}")
                return emptyList()
            }
        return jar.use { jf ->
            jf
                .entries()
                .asSequence()
                .filter { entry ->
                    !entry.isDirectory &&
                        entry.name.startsWith(resourceBase) &&
                        (entry.name.endsWith(".yaml") || entry.name.endsWith(".yml"))
                }.mapNotNull { entry ->
                    try {
                        jf.getInputStream(entry).use { stream ->
                            mapper.readValue<SkillYamlDef>(stream)
                        }
                    } catch (ex: MismatchedInputException) {
                        System.err.println("kore-skills: malformed YAML at ${entry.name}: ${ex.message}")
                        null
                    } catch (ex: JsonMappingException) {
                        System.err.println("kore-skills: malformed YAML at ${entry.name}: ${ex.message}")
                        null
                    }
                }.toList()
        }
    }

    private fun loadFromFilesystem(): List<SkillYamlDef> {
        val dir = File(skillsDirectory)
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return dir
            .walkTopDown()
            .filter { it.isFile && (it.extension == "yaml" || it.extension == "yml") }
            .mapNotNull { parseSkillFileSafely(it) }
            .toList()
    }

    private fun parseSkillFileSafely(file: File): SkillYamlDef? =
        try {
            mapper.readValue<SkillYamlDef>(file)
        } catch (ex: MismatchedInputException) {
            System.err.println("kore-skills: malformed YAML at ${file.path}: ${ex.message}")
            null
        } catch (ex: JsonMappingException) {
            System.err.println("kore-skills: malformed YAML at ${file.path}: ${ex.message}")
            null
        }
}
