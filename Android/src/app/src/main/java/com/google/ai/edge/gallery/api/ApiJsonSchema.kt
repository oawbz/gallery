/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.api

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.math.BigDecimal

/** A deliberately explicit JSON Schema subset. Unsupported constraints never pass silently. */
internal object ApiJsonSchema {
  private val keywords = setOf("type","properties","required","additionalProperties","items",
    "minItems","maxItems","uniqueItems","minLength","maxLength","minimum","maximum",
    "exclusiveMinimum","exclusiveMaximum","multipleOf","enum","const","anyOf","allOf","oneOf",
    "minProperties","maxProperties","\$ref","\$defs","definitions",
    "title","description","default","examples","\$schema","\$id")
  private val types = setOf("object","array","string","number","integer","boolean","null")
  fun check(root: JsonObject) {
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<JsonObject,Boolean>())
    fun visit(node: JsonElement, depth: Int) {
      if (depth > 64) invalid("JSON schema nesting exceeds 64 levels")
      if (node.isJsonPrimitive && node.asJsonPrimitive.isBoolean) return
      if (!node.isJsonObject) invalid("Schema must be an object or boolean")
      val s = node.asJsonObject
      if (!seen.add(s)) return
      s.keySet().firstOrNull { it !in keywords }?.let { invalid("Unsupported JSON schema keyword: $it") }
      s["type"]?.let { value ->
        val names = if (value.isJsonArray) value.asJsonArray.toList() else listOf(value)
        if (names.isEmpty() || names.any { !it.isJsonPrimitive || !it.asJsonPrimitive.isString || it.asString !in types }) invalid("Invalid JSON schema type")
      }
      for (key in listOf("properties","\$defs","definitions")) s[key]?.let {
        if (!it.isJsonObject) invalid("$key must be an object")
        it.asJsonObject.entrySet().forEach { (_,child) -> visit(child,depth + 1) }
      }
      s["required"]?.let { if (!it.isJsonArray || it.asJsonArray.any { p -> !p.isJsonPrimitive || !p.asJsonPrimitive.isString }) invalid("required must be a string array") }
      for (key in listOf("items","additionalProperties")) s[key]?.let { visit(it,depth + 1) }
      for (key in listOf("anyOf","allOf","oneOf")) s[key]?.let {
        if (!it.isJsonArray || it.asJsonArray.size() == 0) invalid("$key must be a nonempty schema array")
        it.asJsonArray.forEach { child -> visit(child,depth + 1) }
      }
      for (key in listOf("uniqueItems")) s[key]?.let { if (!it.isJsonPrimitive || !it.asJsonPrimitive.isBoolean) invalid("$key must be boolean") }
      for (key in listOf("minItems","maxItems","minLength","maxLength","minProperties","maxProperties")) s[key]?.let {
        if (!it.isJsonPrimitive || !it.asJsonPrimitive.isNumber || it.asBigDecimal < BigDecimal.ZERO || it.asBigDecimal.stripTrailingZeros().scale() > 0) invalid("$key must be a nonnegative integer")
      }
      for (key in listOf("minimum","maximum","exclusiveMinimum","exclusiveMaximum","multipleOf")) s[key]?.let {
        if (!it.isJsonPrimitive || !it.asJsonPrimitive.isNumber) invalid("$key must be numeric")
        if (key == "multipleOf" && it.asBigDecimal <= BigDecimal.ZERO) invalid("multipleOf must be positive")
      }
      s["enum"]?.let { if (!it.isJsonArray || it.asJsonArray.size() == 0) invalid("enum must be nonempty") }
      s["\$ref"]?.let {
        if (!it.isJsonPrimitive || !it.asJsonPrimitive.isString) invalid("\$ref must be a string")
        visit(resolve(root,it.asString),depth + 1)
      }
    }
    visit(root,0)
  }
  private fun resolve(root: JsonObject, ref: String): JsonElement {
    if (ref == "#") return root
    if (!ref.startsWith("#/")) invalid("Only local JSON schema references are supported")
    var node: JsonElement = root
    for (key in ref.removePrefix("#/").split('/')) {
      val name = key.replace("~1","/").replace("~0","~")
      node = if (node.isJsonObject) node.asJsonObject[name] ?: invalid("Unresolved JSON schema reference")
        else invalid("Invalid JSON schema reference")
    }
    return node
  }
  fun matches(value: JsonElement, schema: JsonObject): Boolean = matchesNode(value,schema,schema,0)
  private fun matchesNode(v: JsonElement, node: JsonElement, root: JsonObject, depth: Int): Boolean {
    if (depth > 64) return false
    if (node.isJsonPrimitive) return node.asBoolean
    val s = node.asJsonObject
    s["\$ref"]?.let { if (!matchesNode(v,resolve(root,it.asString),root,depth + 1)) return false }
    s["type"]?.let {
      val names = if (it.isJsonArray) it.asJsonArray.map { t -> t.asString } else listOf(it.asString)
      if (names.none { name -> when(name) {
        "null" -> v.isJsonNull
        "object" -> v.isJsonObject
        "array" -> v.isJsonArray
        "string" -> v.isJsonPrimitive && v.asJsonPrimitive.isString
        "boolean" -> v.isJsonPrimitive && v.asJsonPrimitive.isBoolean
        "number" -> v.isJsonPrimitive && v.asJsonPrimitive.isNumber
        "integer" -> v.isJsonPrimitive && v.asJsonPrimitive.isNumber && v.asBigDecimal.stripTrailingZeros().scale() <= 0
        else -> false
      } }) return false
    }
    s["enum"]?.let { if (it.asJsonArray.none { entry -> entry == v }) return false }
    s["const"]?.let { if (it != v) return false }
    for (key in listOf("anyOf","allOf","oneOf")) s[key]?.let {
      val results = it.asJsonArray.map { child -> matchesNode(v,child,root,depth + 1) }
      if (when(key) { "anyOf" -> results.none { result -> result }; "allOf" -> results.any { result -> !result }; else -> results.count { result -> result } != 1 }) return false
    }
    fun sizeOk(size: Int, min: String, max: String) =
      (s[min] == null || BigDecimal(size) >= s[min].asBigDecimal) && (s[max] == null || BigDecimal(size) <= s[max].asBigDecimal)
    if (v.isJsonObject) {
      val obj = v.asJsonObject
      if (!sizeOk(obj.size(),"minProperties","maxProperties")) return false
      if (s["required"]?.asJsonArray?.any { !obj.has(it.asString) } == true) return false
      val properties = s.getAsJsonObject("properties") ?: JsonObject()
      for ((key,entry) in obj.entrySet()) {
        val child = properties[key] ?: s["additionalProperties"]
        if (child != null && !matchesNode(entry,child,root,depth + 1)) return false
      }
    }
    if (v.isJsonArray) {
      val values = v.asJsonArray.toList()
      if (!sizeOk(values.size,"minItems","maxItems")) return false
      if (s["uniqueItems"]?.asBoolean == true && values.distinct().size != values.size) return false
      s["items"]?.let { child -> if (values.any { !matchesNode(it,child,root,depth + 1) }) return false }
    }
    if (v.isJsonPrimitive && v.asJsonPrimitive.isString && !sizeOk(v.asString.codePointCount(0,v.asString.length),"minLength","maxLength")) return false
    if (v.isJsonPrimitive && v.asJsonPrimitive.isNumber) {
      val number = v.asBigDecimal
      if (s["minimum"]?.let { number < it.asBigDecimal } == true || s["maximum"]?.let { number > it.asBigDecimal } == true ||
          s["exclusiveMinimum"]?.let { number <= it.asBigDecimal } == true || s["exclusiveMaximum"]?.let { number >= it.asBigDecimal } == true ||
          s["multipleOf"]?.let { number.remainder(it.asBigDecimal).compareTo(BigDecimal.ZERO) != 0 } == true) return false
    }
    return true
  }
}
