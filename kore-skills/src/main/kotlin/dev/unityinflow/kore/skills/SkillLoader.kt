package dev.unityinflow.kore.skills

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File
import java.io.IOException
import java.net.URL

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
        // URL.getPath() does NOT URL-decode, so paths containing spaces
        // (common on macOS: "/Users/foo/My Project/") arrive as "My%20Project"
        // and JarFile(File(...)) fails with FileNotFoundException (ME-07).
        // JarURLConnection handles URL decoding and nested JAR protocols
        // correctly — use it instead of string-slicing the URL.
        val jar =
            try {
                val connection = url.openConnection() as java.net.JarURLConnection
                connection.useCaches = false
                connection.jarFile
            } catch (ex: IOException) {
                System.err.println("kore-skills: cannot open jar $url: ${ex.message}")
                return emptyList()
            } catch (ex: ClassCastException) {
                System.err.println("kore-skills: unsupported jar URL $url: ${ex.message}")
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
                    } catch (ex: JsonProcessingException) {
                        // Catches JsonParseException (syntax) + JsonMappingException
                        // + MismatchedInputException — all subclasses.
                        System.err.println("kore-skills: malformed YAML at ${entry.name}: ${ex.message}")
                        null
                    } catch (ex: IOException) {
                        System.err.println("kore-skills: cannot read skill file ${entry.name}: ${ex.message}")
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
        } catch (ex: JsonProcessingException) {
            // Catches JsonParseException (syntax errors) + JsonMappingException
            // + MismatchedInputException — all Jackson failure subclasses.
            // ME-01: previously only Mismatched/Mapping were caught, so a
            // single file with invalid YAML syntax (unterminated string,
            // tab indentation) crashed loadAll() and the Spring context.
            System.err.println("kore-skills: malformed YAML at ${file.path}: ${ex.message}")
            null
        } catch (ex: IOException) {
            System.err.println("kore-skills: cannot read skill file ${file.path}: ${ex.message}")
            null
        }
}
