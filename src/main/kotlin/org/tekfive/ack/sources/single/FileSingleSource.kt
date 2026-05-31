package org.tekfive.ack.sources.single

import java.io.File

/**
 * Single-property source that returns the UTF-8 contents of a file.
 *
 * @param propertyName property name exposed by this source.
 * @property file file read lazily when the property is requested.
 */
class FileSingleSource(
    propertyName: String,
    val file: File,
) : SinglePropertySource(propertyName, { file.readText(Charsets.UTF_8) }) {

    init {
        require(file.isFile) { "File content source: $propertyName cannot find file at: ${file.absolutePath}" }
        require(file.canRead()) { "File content source: $propertyName cannot read file at: ${file.absolutePath}" }
    }

    /** Creates a file-backed source from [filePath]. */
    constructor(propertyName: String, filePath: String) : this(propertyName, File(filePath))
}
