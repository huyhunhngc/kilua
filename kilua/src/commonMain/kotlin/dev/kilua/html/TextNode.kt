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

package dev.kilua.html

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import dev.kilua.compose.ComponentNode
import dev.kilua.core.ComponentBase
import dev.kilua.core.DefaultRenderConfig
import dev.kilua.core.RenderConfig
import dev.kilua.core.SafeDomFactory
import org.w3c.dom.Text

/**
 * Text node component.
 */
public open class TextNode(
    text: String,
    renderConfig: RenderConfig = DefaultRenderConfig(),
) : ComponentBase(SafeDomFactory.createTextNode(text, renderConfig), renderConfig) {

    /**
     * The DOM text node.
     */
    public open val textNode: Text
        get() = node?.unsafeCast<Text>()
            ?: throw IllegalStateException("Can't use DOM element with the current render configuration")

    /**
     * The DOM text node or null if the current render configuration doesn't support DOM.
     */
    public open val textNodeNullable: Text? = node?.unsafeCast<Text>()

    /**
     * The DOM text node or null if the current render configuration doesn't support DOM.
     */
    public open val textNodeAvailable: Boolean = node != null

    /**
     * Whether to skip updating the DOM text node of the current component.
     */
    protected val skipUpdate: Boolean = node == null

    /**
     * The text of the node.
     */
    public open var text: String by updatingProperty(text, skipUpdate) {
        textNode.data = it
    }

    override var visible: Boolean by updatingProperty(true, skipUpdate) {
        textNode.data = if (it) text else ""
    }

    override fun renderToStringBuilder(builder: StringBuilder) {
        builder.append(text)
    }
}

/**
 * Creates a [TextNode] component.
 * @param text the text of the node
 * @param content a function for setting up the component
 * @return a [TextNode] component
 */
@Composable
public fun textNode(text: String, content: TextNode.() -> Unit = {}): TextNode {
    // Always using DefaultRenderConfig because of plus operator String receiver.
    val component = remember { TextNode(text, DefaultRenderConfig()) }
    DisposableEffect(component.componentId) {
        component.onInsert()
        onDispose {
            component.onRemove()
        }
    }
    ComponentNode(component, {
        set(text) { updateProperty(TextNode::text, it) }
    }) {
        content(component)
    }
    return component
}

/**
 * Creates a [TextNode] component with unary + operator.
 */
@Composable
public operator fun String.unaryPlus() {
    textNode(this)
}
