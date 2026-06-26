package com.inbyte.imagedescriber.inference

import android.content.Context
import android.util.Log
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InstructionRepository @Inject constructor() {

    private var instructions: Map<String, List<String>> = emptyMap()

    fun load(context: Context) {
        if (instructions.isNotEmpty()) return
        val map = mutableMapOf<String, MutableList<String>>()
        context.assets.open("fairy_tale_instructions.jsonl").bufferedReader().forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val obj = JSONObject(line)
            val category = obj.getString("category")
            val instruction = obj.getString("instruction")
            map.getOrPut(category) { mutableListOf() }.add(instruction)
        }
        instructions = map
        Log.d("InByteVM", "InstructionRepository loaded: ${map.keys} total=${map.values.sumOf { it.size }}")
    }

    fun categories(): Set<String> = instructions.keys

    fun findInstruction(userInput: String): Pair<String, String>? {
        val lower = userInput.lowercase()
        val category = instructions.keys.firstOrNull { lower.contains(it) } ?: return null
        val list = instructions[category] ?: return null
        return category to list.random()
    }
}
