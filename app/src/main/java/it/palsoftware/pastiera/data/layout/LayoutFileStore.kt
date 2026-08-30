package it.palsoftware.pastiera.data.layout

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import android.view.KeyEvent
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Manages custom keyboard layout files on device storage as well as metadata
 * retrieval from both local files and bundled assets.
 */
object LayoutFileStore {
    private const val TAG = "LayoutFileStore"
    private const val LAYOUTS_DIR_NAME = "keyboard_layouts"

    private val keyboardLayoutNameToKeyCode = mapOf(
        "KEYCODE_Q" to KeyEvent.KEYCODE_Q,
        "KEYCODE_W" to KeyEvent.KEYCODE_W,
        "KEYCODE_E" to KeyEvent.KEYCODE_E,
        "KEYCODE_R" to KeyEvent.KEYCODE_R,
        "KEYCODE_T" to KeyEvent.KEYCODE_T,
        "KEYCODE_Y" to KeyEvent.KEYCODE_Y,
        "KEYCODE_U" to KeyEvent.KEYCODE_U,
        "KEYCODE_I" to KeyEvent.KEYCODE_I,
        "KEYCODE_O" to KeyEvent.KEYCODE_O,
        "KEYCODE_P" to KeyEvent.KEYCODE_P,
        "KEYCODE_A" to KeyEvent.KEYCODE_A,
        "KEYCODE_S" to KeyEvent.KEYCODE_S,
        "KEYCODE_D" to KeyEvent.KEYCODE_D,
        "KEYCODE_F" to KeyEvent.KEYCODE_F,
        "KEYCODE_G" to KeyEvent.KEYCODE_G,
        "KEYCODE_H" to KeyEvent.KEYCODE_H,
        "KEYCODE_J" to KeyEvent.KEYCODE_J,
        "KEYCODE_K" to KeyEvent.KEYCODE_K,
        "KEYCODE_L" to KeyEvent.KEYCODE_L,
        "KEYCODE_Z" to KeyEvent.KEYCODE_Z,
        "KEYCODE_X" to KeyEvent.KEYCODE_X,
        "KEYCODE_C" to KeyEvent.KEYCODE_C,
        "KEYCODE_V" to KeyEvent.KEYCODE_V,
        "KEYCODE_B" to KeyEvent.KEYCODE_B,
        "KEYCODE_N" to KeyEvent.KEYCODE_N,
        "KEYCODE_M" to KeyEvent.KEYCODE_M,
        "KEYCODE_1" to KeyEvent.KEYCODE_1,
        "KEYCODE_2" to KeyEvent.KEYCODE_2,
        "KEYCODE_3" to KeyEvent.KEYCODE_3,
        "KEYCODE_4" to KeyEvent.KEYCODE_4,
        "KEYCODE_5" to KeyEvent.KEYCODE_5,
        "KEYCODE_6" to KeyEvent.KEYCODE_6,
        "KEYCODE_7" to KeyEvent.KEYCODE_7,
        "KEYCODE_8" to KeyEvent.KEYCODE_8,
        "KEYCODE_9" to KeyEvent.KEYCODE_9,
        "KEYCODE_0" to KeyEvent.KEYCODE_0,
        "KEYCODE_GRAVE" to KeyEvent.KEYCODE_GRAVE,
        "KEYCODE_MINUS" to KeyEvent.KEYCODE_MINUS,
        "KEYCODE_EQUALS" to KeyEvent.KEYCODE_EQUALS,
        "KEYCODE_LEFT_BRACKET" to KeyEvent.KEYCODE_LEFT_BRACKET,
        "KEYCODE_RIGHT_BRACKET" to KeyEvent.KEYCODE_RIGHT_BRACKET,
        "KEYCODE_BACKSLASH" to KeyEvent.KEYCODE_BACKSLASH,
        "KEYCODE_SEMICOLON" to KeyEvent.KEYCODE_SEMICOLON,
        "KEYCODE_APOSTROPHE" to KeyEvent.KEYCODE_APOSTROPHE,
        "KEYCODE_COMMA" to KeyEvent.KEYCODE_COMMA,
        "KEYCODE_PERIOD" to KeyEvent.KEYCODE_PERIOD,
        "KEYCODE_SLASH" to KeyEvent.KEYCODE_SLASH
    )
    private val keyboardLayoutKeyCodeToName = keyboardLayoutNameToKeyCode.entries.associate { (name, code) ->
        code to name
    }

    enum class LayoutImportError {
        MALFORMED_JSON,
        MISSING_MAPPINGS,
        MAPPINGS_NOT_OBJECT,
        EMPTY_MAPPINGS,
        NO_SUPPORTED_MAPPINGS,
        INVALID_MAPPING,
        WRITE_FAILED
    }

    sealed interface LayoutImportResult {
        data class Success(val layoutName: String) : LayoutImportResult
        data class Failure(
            val error: LayoutImportError,
            val detail: String? = null
        ) : LayoutImportResult
    }

    private sealed interface LayoutParseResult {
        data class Success(val layout: Map<Int, LayoutMapping>) : LayoutParseResult
        data class Failure(
            val error: LayoutImportError,
            val detail: String? = null
        ) : LayoutParseResult
    }

    fun getLayoutsDirectory(context: Context): File {
        return File(context.filesDir, LAYOUTS_DIR_NAME).apply {
            if (!exists()) mkdirs()
        }
    }

    fun getLayoutFile(context: Context, layoutName: String): File {
        val layoutsDir = getLayoutsDirectory(context)
        return File(layoutsDir, "$layoutName.json")
    }

    fun loadLayoutFromFile(file: File): Map<Int, LayoutMapping>? {
        return try {
            if (!file.exists() || !file.canRead()) {
                Log.w(TAG, "File does not exist or cannot be read: ${file.absolutePath}")
                return null
            }
            val jsonString = file.readText()
            parseLayoutJson(jsonString)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading layout from file: ${file.absolutePath}", e)
            null
        }
    }

    fun loadLayoutFromStream(inputStream: InputStream): Map<Int, LayoutMapping>? {
        return try {
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            parseLayoutJson(jsonString)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading layout from stream", e)
            null
        }
    }

    private fun parseLayoutJson(jsonString: String): Map<Int, LayoutMapping>? {
        return when (val result = validateLayoutJson(jsonString)) {
            is LayoutParseResult.Success -> result.layout
            is LayoutParseResult.Failure -> {
                Log.e(TAG, "Invalid layout JSON: ${result.error}${result.detail?.let { " ($it)" }.orEmpty()}")
                null
            }
        }
    }

    private fun validateLayoutJson(jsonString: String): LayoutParseResult {
        val jsonObject = try {
            JSONObject(jsonString)
        } catch (e: Exception) {
            return LayoutParseResult.Failure(LayoutImportError.MALFORMED_JSON, e.message)
        }

        if (!jsonObject.has("mappings")) {
            return LayoutParseResult.Failure(LayoutImportError.MISSING_MAPPINGS)
        }
        val mappingsObject = jsonObject.opt("mappings") as? JSONObject
            ?: return LayoutParseResult.Failure(LayoutImportError.MAPPINGS_NOT_OBJECT)
        if (mappingsObject.length() == 0) {
            return LayoutParseResult.Failure(LayoutImportError.EMPTY_MAPPINGS)
        }

        val layout = mutableMapOf<Int, LayoutMapping>()
        val keys = mappingsObject.keys()
        while (keys.hasNext()) {
            val keyName = keys.next()
            val keyCode = keyboardLayoutNameToKeyCode[keyName] ?: continue
            val mappingObj = mappingsObject.opt(keyName) as? JSONObject
                ?: return invalidMapping(keyName, "mapping must be an object")
            val lowercase = mappingObj.requiredNonEmptyString("lowercase")
                ?: return invalidMapping(keyName, "lowercase must be a non-empty string")
            val uppercase = mappingObj.requiredNonEmptyString("uppercase")
                ?: return invalidMapping(keyName, "uppercase must be a non-empty string")

            val multiTapEnabled = when {
                !mappingObj.has("multiTapEnabled") -> false
                mappingObj.opt("multiTapEnabled") is Boolean -> mappingObj.getBoolean("multiTapEnabled")
                else -> return invalidMapping(keyName, "multiTapEnabled must be a boolean")
            }
            val taps = when (val tapsResult = parseTaps(mappingObj, keyName)) {
                is TapsParseResult.Success -> tapsResult.taps
                is TapsParseResult.Failure -> return tapsResult.failure
            }
            if (multiTapEnabled && taps.size < 2) {
                return invalidMapping(keyName, "multi-tap mappings require at least two non-empty taps")
            }

            layout[keyCode] = LayoutMapping(
                lowercase = lowercase,
                uppercase = uppercase,
                multiTapEnabled = multiTapEnabled,
                taps = if (multiTapEnabled) taps else emptyList()
            )
        }

        if (layout.isEmpty()) {
            return LayoutParseResult.Failure(LayoutImportError.NO_SUPPORTED_MAPPINGS)
        }
        Log.d(TAG, "Parsed layout with ${layout.size} mappings")
        return LayoutParseResult.Success(layout)
    }

    private sealed interface TapsParseResult {
        data class Success(val taps: List<TapMapping>) : TapsParseResult
        data class Failure(val failure: LayoutParseResult.Failure) : TapsParseResult
    }

    private fun parseTaps(mappingObj: JSONObject, keyName: String): TapsParseResult {
        if (!mappingObj.has("taps")) return TapsParseResult.Success(emptyList())
        val tapsArray = mappingObj.opt("taps") as? JSONArray
            ?: return TapsParseResult.Failure(invalidMapping(keyName, "taps must be an array"))
        val taps = mutableListOf<TapMapping>()
        for (index in 0 until tapsArray.length()) {
            val tapObj = tapsArray.opt(index) as? JSONObject
                ?: return TapsParseResult.Failure(invalidMapping(keyName, "tap $index must be an object"))
            val tapLower = tapObj.optionalString("lowercase")
                ?: return TapsParseResult.Failure(invalidMapping(keyName, "tap $index lowercase must be a string"))
            val tapUpper = tapObj.optionalString("uppercase")
                ?: return TapsParseResult.Failure(invalidMapping(keyName, "tap $index uppercase must be a string"))
            if (tapLower.isNotEmpty() || tapUpper.isNotEmpty()) {
                taps.add(TapMapping(tapLower, tapUpper))
            }
        }
        return TapsParseResult.Success(taps)
    }

    private fun invalidMapping(keyName: String, detail: String) =
        LayoutParseResult.Failure(LayoutImportError.INVALID_MAPPING, "$keyName: $detail")

    private fun JSONObject.requiredNonEmptyString(key: String): String? {
        val value = opt(key)
        return (value as? String)?.takeIf { it.isNotEmpty() }
    }

    private fun JSONObject.optionalString(key: String): String? {
        if (!has(key)) return ""
        return opt(key) as? String
    }

    fun saveLayout(
        context: Context,
        layoutName: String,
        layout: Map<Int, LayoutMapping>,
        name: String? = null,
        description: String? = null
    ): Boolean {
        return try {
            val layoutFile = getLayoutFile(context, layoutName)
            val jsonString = buildLayoutJsonString(layoutName, layout, name, description)
            FileOutputStream(layoutFile).use { outputStream ->
                outputStream.write(jsonString.toByteArray())
            }
            Log.d(TAG, "Saved layout: $layoutName to ${layoutFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error saving layout: $layoutName", e)
            false
        }
    }

    fun buildLayoutJsonString(
        layoutName: String,
        layout: Map<Int, LayoutMapping>,
        name: String?,
        description: String?
    ): String {
        val jsonObject = JSONObject()
        name?.takeIf { it.isNotBlank() }?.let { jsonObject.put("name", it) }
        description?.takeIf { it.isNotBlank() }?.let { jsonObject.put("description", it) }

        val mappingsObject = JSONObject()
        layout.forEach { (keyCode, mapping) ->
            val keyName = keyboardLayoutKeyCodeToName[keyCode]
            if (keyName != null) {
                val mappingObj = JSONObject()
                mappingObj.put("lowercase", mapping.lowercase)
                mappingObj.put("uppercase", mapping.uppercase)
                if (mapping.multiTapEnabled && mapping.taps.isNotEmpty()) {
                    mappingObj.put("multiTapEnabled", true)
                    val tapsArray = org.json.JSONArray()
                    mapping.taps.forEach { tap ->
                        val tapObj = JSONObject()
                        tapObj.put("lowercase", tap.lowercase)
                        tapObj.put("uppercase", tap.uppercase)
                        tapsArray.put(tapObj)
                    }
                    mappingObj.put("taps", tapsArray)
                }
                mappingsObject.put(keyName, mappingObj)
            }
        }

        jsonObject.put("mappings", mappingsObject)
        return jsonObject.toString(2)
    }

    fun saveLayoutFromJson(
        context: Context,
        layoutName: String,
        jsonString: String
    ): LayoutImportResult {
        return try {
            when (val validation = validateLayoutJson(jsonString)) {
                is LayoutParseResult.Failure -> {
                    Log.e(TAG, "Invalid JSON format, cannot save layout $layoutName: ${validation.error}")
                    return LayoutImportResult.Failure(validation.error, validation.detail)
                }
                is LayoutParseResult.Success -> Unit
            }

            val layoutFile = getLayoutFile(context, layoutName)
            FileOutputStream(layoutFile).use { outputStream ->
                outputStream.write(jsonString.toByteArray())
            }

            Log.d(TAG, "Saved layout from JSON: $layoutName to ${layoutFile.absolutePath}")
            LayoutImportResult.Success(layoutName)
        } catch (e: Exception) {
            Log.e(TAG, "Error saving layout from JSON: $layoutName", e)
            LayoutImportResult.Failure(LayoutImportError.WRITE_FAILED, e.message)
        }
    }

    fun getCustomLayoutNames(context: Context): List<String> {
        return try {
            val layoutsDir = getLayoutsDirectory(context)
            val layoutFiles = layoutsDir.listFiles { file ->
                file.isFile && file.name.endsWith(".json")
            }
            layoutFiles?.map { it.name.removeSuffix(".json") }?.sorted() ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting custom layout names", e)
            emptyList()
        }
    }

    fun getLayoutMetadata(context: Context, layoutName: String): LayoutMetadata? {
        return try {
            val layoutFile = getLayoutFile(context, layoutName)
            if (!layoutFile.exists() || !layoutFile.canRead()) {
                return null
            }

            val jsonString = layoutFile.readText()
            val jsonObject = JSONObject(jsonString)
            LayoutMetadata(
                name = jsonObject.optString("name", layoutName),
                description = jsonObject.optString("description", "")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting layout metadata: $layoutName", e)
            null
        }
    }

    fun getLayoutMetadataFromAssets(assets: AssetManager, layoutName: String): LayoutMetadata? {
        return try {
            val filePath = "common/layouts/$layoutName.json"
            val inputStream: InputStream = assets.open(filePath)
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            val jsonObject = JSONObject(jsonString)
            LayoutMetadata(
                name = jsonObject.optString("name", layoutName),
                description = jsonObject.optString("description", "")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting layout metadata from assets: $layoutName", e)
            null
        }
    }

    fun deleteLayout(context: Context, layoutName: String): Boolean {
        return try {
            val layoutFile = getLayoutFile(context, layoutName)
            if (layoutFile.exists()) {
                val deleted = layoutFile.delete()
                if (deleted) {
                    Log.d(TAG, "Deleted layout: $layoutName")
                }
                deleted
            } else {
                Log.w(TAG, "Layout file does not exist: $layoutName")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting layout: $layoutName", e)
            false
        }
    }

    fun layoutExists(context: Context, layoutName: String): Boolean {
        return getLayoutFile(context, layoutName).exists()
    }

    fun importLayoutFromFile(
        context: Context,
        sourceFile: File,
        targetLayoutName: String
    ): Boolean {
        return try {
            if (!sourceFile.exists() || !sourceFile.canRead()) {
                Log.e(TAG, "Source file does not exist or cannot be read: ${sourceFile.absolutePath}")
                return false
            }

            val layout = loadLayoutFromFile(sourceFile)
            if (layout == null) {
                Log.e(TAG, "Invalid layout file, cannot import: ${sourceFile.absolutePath}")
                return false
            }

            val targetFile = getLayoutFile(context, targetLayoutName)
            sourceFile.copyTo(targetFile, overwrite = true)

            Log.d(TAG, "Imported layout from ${sourceFile.absolutePath} to ${targetFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error importing layout from file", e)
            false
        }
    }

    data class LayoutMetadata(
        val name: String,
        val description: String
    )
}
