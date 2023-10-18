/*
 * Copyright (c) 2023 Robert Jaros
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package dev.kilua.utils

public actual external class Object : JsAny

@JsFun("() => ( {} )")
public actual external fun obj(): Object

@JsFun("ref => typeof document !== 'undefined'")
private external fun isDom(): Boolean

public actual val isDom: Boolean by lazy { isDom() }

public actual annotation class JsNonModule actual constructor()

/**
 * Helper function for creating JavaScript objects with given type.
 */
public inline fun <T : JsAny> obj(init: T.() -> Unit): T {
    return (obj().unsafeCast<T>()).apply(init)
}

public actual fun size(array: Object): Int = array.cast<JsArray<*>>().length

public inline fun <reified T : JsAny> JsArray<T>.toArray(): Array<T> {
    return Array(length) { get(it)!! }
}

public fun <T : JsAny> JsArray<T>.toList(): List<T> {
    return List(length) { get(it)!! }
}
