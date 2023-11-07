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

package dev.kilua.form

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import dev.kilua.KiluaScope
import dev.kilua.compose.ComponentNode
import dev.kilua.core.ComponentBase
import dev.kilua.core.DefaultRenderConfig
import dev.kilua.core.RenderConfig
import dev.kilua.externals.Object
import dev.kilua.externals.get
import dev.kilua.externals.keys
import dev.kilua.externals.obj
import dev.kilua.externals.set
import dev.kilua.externals.toJsObject
import dev.kilua.html.Tag
import dev.kilua.html.helpers.PropertyListBuilder
import dev.kilua.state.WithStateFlow
import dev.kilua.types.KFile
import dev.kilua.utils.JSON
import dev.kilua.utils.Serialization
import dev.kilua.utils.Serialization.toObj
import dev.kilua.utils.cast
import dev.kilua.utils.nativeMapOf
import dev.kilua.utils.toKebabCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.overwriteWith
import kotlinx.serialization.serializer
import org.w3c.dom.HTMLFormElement
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1

/**
 * Form methods.
 */
public enum class FormMethod {
    Get,
    Post;

    public val value: String = name.toKebabCase()
    override fun toString(): String {
        return value
    }
}

/**
 * Form encoding types.
 */
public enum class FormEnctype(public val value: String) {
    Urlencoded("application/x-www-form-urlencoded"),
    Multipart("multipart/form-data"),
    Plain("text/plain");

    override fun toString(): String {
        return value
    }
}

/**
 * Form methods.
 */
public enum class FormAutocomplete {
    On,
    Off;

    public val value: String = name.toKebabCase()
    override fun toString(): String {
        return value
    }
}

/**
 * HTML Form component.
 */
public open class Form<K : Any>(
    method: FormMethod? = null, action: String? = null, enctype: FormEnctype? = null,
    novalidate: Boolean? = null,
    protected val serializer: KSerializer<K>? = null,
    protected val customSerializers: Map<KClass<*>, KSerializer<*>>? = null,
    className: String? = null,
    renderConfig: RenderConfig = DefaultRenderConfig()
) :
    Tag<HTMLFormElement>("form", className, renderConfig), WithStateFlow<K> {

    /**
     * The method attribute of the generated HTML form element.
     */
    public open var method: FormMethod? by updatingProperty(method, skipUpdate) {
        if (it != null) {
            element.method = it.toString()
        } else {
            element.removeAttribute("method")
        }
    }

    /**
     * The action attribute of the generated HTML form element.
     */
    public open var action: String? by updatingProperty(action, skipUpdate) {
        if (it != null) {
            element.action = it.toString()
        } else {
            element.removeAttribute("action")
        }
    }

    /**
     * The enctype attribute of the generated HTML form element.
     */
    public open var enctype: FormEnctype? by updatingProperty(enctype, skipUpdate) {
        if (it != null) {
            element.enctype = it.toString()
        } else {
            element.removeAttribute("enctype")
        }
    }

    /**
     * The name attribute of the generated HTML form element.
     */
    public open var name: String? by updatingProperty(skipUpdate = skipUpdate) {
        if (it != null) {
            element.name = it
        } else {
            element.removeAttribute("name")
        }
    }

    /**
     * The target attribute of the generated HTML form element.
     */
    public open var target: String? by updatingProperty(skipUpdate = skipUpdate) {
        if (it != null) {
            element.target = it
        } else {
            element.removeAttribute("target")
        }
    }

    /**
     * The target attribute of the generated HTML form element.
     */
    public open var novalidate: Boolean? by updatingProperty(novalidate, skipUpdate) {
        if (it != null) {
            element.noValidate = it
        } else {
            element.removeAttribute("novalidate")
        }
    }

    /**
     * The target attribute of the generated HTML form element.
     */
    public open var autocomplete: FormAutocomplete? by updatingProperty(skipUpdate = skipUpdate) {
        if (it != null) {
            element.autocomplete = it.toString()
        } else {
            element.removeAttribute("autocomplete")
        }
    }

    /**
     * A validator message generator function.
     */
    public open var validatorMessage: ((Form<K>) -> String?)? = null

    /**
     * A validator function.
     */
    public open var validator: ((Form<K>) -> Boolean?)? = null

    /**
     * Keeps values of the form not bound to any input components.
     */
    protected val dataMap: MutableMap<String, Any?> = nativeMapOf()

    /**
     * Helper functions to convert data between the form and the model.
     */
    protected val mapToObjectConverter: ((Map<String, Any?>) -> Object)?
    protected val mapToClassConverter: ((Map<String, Any?>) -> K)?
    protected val classToObjectConverter: ((K) -> Object)?

    /**
     * Keeps all form controls.
     */
    protected val fields: LinkedHashMap<String, FormControl<*>> = linkedMapOf()

    /**
     * Keeps all form controls parameters.
     */
    protected val fieldsParams: MutableMap<String, Any> = mutableMapOf()

    /**
     * Determines if the data model was set outside compose.
     */
    protected var dataSet: Boolean = false

    @OptIn(ExperimentalSerializationApi::class)
    protected val JsonInstance: Json? = serializer?.let {
        Json(
            from = (Serialization.customConfiguration ?: Json.Default)
        ) {
            encodeDefaults = true
            explicitNulls = false
            serializersModule = serializersModule.overwriteWith(SerializersModule {
                customSerializers?.forEach { (kclass, serializer) ->
                    contextual(kclass.cast(), serializer.cast<KSerializer<Any>>())
                }
            })
        }
    }

    /**
     * Whether the data state flow is initialized.
     */
    protected var initializedDataStateFlow: Boolean = false

    /**
     * Internal mutable state flow instance (lazily initialized)
     */
    protected val _mutableDataStateFlow: MutableStateFlow<K> by lazy {
        initializedDataStateFlow = true
        MutableStateFlow(getData())
    }

    override val stateFlow: StateFlow<K>
        get() = _mutableDataStateFlow.asStateFlow()

    override val mutableStateFlow: MutableStateFlow<K>
        get() = _mutableDataStateFlow

    protected fun updateStateFlow(value: K) {
        if (initializedDataStateFlow) {
            _mutableDataStateFlow.value = value
        }
    }

    /**
     * Whether the validation results state flow is initialized.
     */
    protected var initializedValidationStateFlow: Boolean = false

    /**
     * Internal mutable state flow instance for validation results (lazily initialized).
     */
    protected val _mutableValidationStateFlow: MutableStateFlow<Validation<K>> by lazy {
        initializedValidationStateFlow = true
        MutableStateFlow(Validation())
    }

    /**
     * Validation results state flow.
     */
    public val validationStateFlow: StateFlow<Validation<K>>
        get() = _mutableValidationStateFlow.asStateFlow()

    init {
        if (renderConfig.isDom) {
            if (method != null) {
                element.method = method.toString()
            }
            if (action != null) {
                element.action = action
            }
            if (enctype != null) {
                element.enctype = enctype.toString()
            }
            if (novalidate != null) {
                element.noValidate = novalidate
            }
        }
        mapToObjectConverter = serializer?.let {
            { map ->
                val json = obj()
                map.forEach { (key, value) ->
                    when (value) {
                        is LocalDate, is LocalDateTime, is LocalTime -> {
                            json[key] = value.toString().toJsObject()
                        }

                        is String -> {
                            json[key] = value.toJsObject()
                        }

                        is Boolean -> {
                            json[key] = value.toJsObject()
                        }

                        is Int -> {
                            json[key] = value.toJsObject()
                        }

                        is Double -> {
                            json[key] = value.toJsObject()
                        }

                        is List<*> -> {
                            @Suppress("UNCHECKED_CAST")
                            (value as? List<KFile>)?.toObj(ListSerializer(KFile.serializer()))?.let {
                                json[key] = it
                            }
                        }

                        else -> {
                            if (value != null) {
                                json[key] = value.cast()
                            }
                        }
                    }
                }
                json
            }
        }
        mapToClassConverter = serializer?.let {
            { map ->
                JsonInstance!!.decodeFromString(serializer, JSON.stringify(mapToObjectConverter!!.invoke(map)))
            }
        }
        classToObjectConverter = serializer?.let {
            {
                JSON.parse(JsonInstance!!.encodeToString(serializer, it))
            }
        }
    }

    /**
     * Sets the values of all the controls from the model.
     * @param model data model
     */
    protected open fun setDataInternal(model: K) {
        val oldData = getData()
        dataMap.clear()
        if (classToObjectConverter != null) {
            val json = classToObjectConverter.invoke(model)
            val keys = keys(json)
            for (key in keys) {
                val jsonValue = json[key]
                if (jsonValue != null) {
                    when (val formField = fields[key]) {
                        is StringFormControl -> formField.value = jsonValue.toString()
                        is DateFormControl -> formField.value = LocalDate.parse(jsonValue.toString())
                        is DateTimeFormControl -> formField.value = LocalDateTime.parse(jsonValue.toString())
                        is TimeFormControl -> formField.value = LocalTime.parse(jsonValue.toString())
                        is TriStateFormControl -> formField.value = jsonValue.toString().toBoolean()
                        is BoolFormControl -> formField.value = jsonValue.toString().toBoolean()
                        is IntFormControl -> formField.value = jsonValue.toString().toInt()
                        is NumberFormControl -> formField.value = jsonValue.toString().toDouble()
                        is KFilesFormControl -> {
                            formField.value = Json.decodeFromString(
                                ListSerializer(KFile.serializer()),
                                JSON.stringify(jsonValue)
                            )
                        }

                        else -> {
                            if (formField != null) {
                                throw IllegalStateException("Unsupported form field type: ${formField::class.simpleName}")
                            } else {
                                dataMap[key] = jsonValue
                            }
                        }
                    }
                } else {
                    fields[key]?.setValue(null)
                }
            }
            fields.forEach { if (!keys.contains(it.key)) it.value.setValue(null) }
        } else {
            val map = model.cast<Map<String, Any?>>()
            map.forEach { (key, value) ->
                if (value != null) {
                    val formField = fields[key]
                    if (formField != null) {
                        formField.setValue(value)
                    } else {
                        dataMap[key] = value
                    }
                } else {
                    fields[key]?.setValue(null)
                }
            }
            fields.forEach { if (!map.contains(it.key)) it.value.setValue(null) }
        }
        if (oldData != model) updateStateFlow(model)
    }

    /**
     * Sets the values of all controls to null.
     */
    protected open fun clearDataInternal() {
        val oldData = getData()
        dataMap.clear()
        fields.forEach { it.value.setValue(null) }
        val newData = getData()
        if (oldData != newData) updateStateFlow(newData)
    }

    /**
     * Sets the values of all the controls from the model.
     * @param model data model
     */
    public open fun setData(model: K) {
        dataSet = true
        setDataInternal(model)
    }

    /**
     * Sets the values of all controls to null.
     */
    public open fun clearData() {
        dataSet = true
        clearDataInternal()
    }

    /**
     * Sets the values of all the controls from the model when not already set by setData or clearData.
     * @param model data model
     */
    @PublishedApi
    internal open fun setDataFromCompose(model: K) {
        if (!dataSet) setDataInternal(model)
    }

    /**
     * Returns current data model.
     * @return data model
     */
    public open fun getData(): K {
        val map = dataMap + fields.entries.associateBy({ it.key }, { it.value.getValue() })
        return mapToClassConverter?.invoke(map.withDefault { null }) ?: map.cast()
    }

    /**
     * Returns current data model as JS object.
     * @return data model as JS object
     */
    public open fun getDataJson(): Object {
        return if (serializer != null) {
            JSON.parse(
                JsonInstance!!.encodeToString(
                    serializer,
                    getData()
                )
            )
        } else {
            mapToObjectConverter!!.invoke(getData().cast())
        }
    }

    /**
     * Binds a form control to the form with a dynamic key.
     * @param key key identifier of the control
     * @param validatorMessage optional function returning validation message
     * @param validator optional validation function
     */
    @Composable
    public open fun <T, C : FormControl<T>> C.bind(
        key: String,
        validatorMessage: ((C) -> String?)? = null,
        validator: ((C) -> Boolean?)? = null
    ): C {
        this@Form.fields[key] = this
        this@Form.fieldsParams[key] = FieldParams(validatorMessage, validator)
        DisposableEffect(key) {
            val job = this@bind.stateFlow.onEach {
                this@Form.updateStateFlow(getData())
            }.launchIn(KiluaScope)
            onDispose {
                job.cancel()
            }
        }
        return this
    }

    /**
     * Bind a string control to the form.
     * @param key key identifier of the control
     * @param validatorMessage optional function returning validation message
     * @param validator optional validation function
     * @return the control itself
     */
    @Composable
    public open fun <C : StringFormControl> C.bind(
        key: KProperty1<K, String?>,
        validatorMessage: ((C) -> String?)? = null,
        validator: ((C) -> Boolean?)? = null
    ): C {
        return bind(key.name, validatorMessage, validator)
    }

    /**
     * Bind a string control to the form bound to custom field type.
     * @param key key identifier of the control
     * @param validatorMessage optional function returning validation message
     * @param validator optional validation function
     * @return the control itself
     */
    @Composable
    public open fun <C : StringFormControl> C.bindCustom(
        key: KProperty1<K, Any?>,
        validatorMessage: ((C) -> String?)? = null,
        validator: ((C) -> Boolean?)? = null
    ): C {
        return bind(key.name, validatorMessage, validator)
    }


    /**
     * Bind a boolean control to the form.
     * @param key key identifier of the control
     * @param validatorMessage optional function returning validation message
     * @param validator optional validation function
     * @return the control itself
     */
    @Composable
    public open fun <C : BoolFormControl> C.bind(
        key: KProperty1<K, Boolean?>,
        validatorMessage: ((C) -> String?)? = null,
        validator: ((C) -> Boolean?)? = null
    ): C {
        return bind(key.name, validatorMessage, validator)
    }

    /**
     * Bind a nullable boolean control to the form.
     * @param key key identifier of the control
     * @param validatorMessage optional function returning validation message
     * @param validator optional validation function
     * @return the control itself
     */
    @Composable
    public open fun <C : TriStateFormControl> C.bind(
        key: KProperty1<K, Boolean?>,
        validatorMessage: ((C) -> String?)? = null,
        validator: ((C) -> Boolean?)? = null
    ): C {
        return bind(key.name, validatorMessage, validator)
    }

    /**
     * Bind an integer control to the form.
     * @param key key identifier of the control
     * @param validatorMessage optional function returning validation message
     * @param validator optional validation function
     * @return the control itself
     */
    @Composable
    public open fun <C : IntFormControl> C.bind(
        key: KProperty1<K, Int?>,
        validatorMessage: ((C) -> String?)? = null,
        validator: ((C) -> Boolean?)? = null
    ): C {
        return bind(key.name, validatorMessage, validator)
    }

    /**
     * Bind a number control to the form.
     * @param key key identifier of the control
     * @param validatorMessage optional function returning validation message
     * @param validator optional validation function
     * @return the control itself
     */
    @Composable
    public open fun <C : NumberFormControl> C.bind(
        key: KProperty1<K, Number?>,
        validatorMessage: ((C) -> String?)? = null,
        validator: ((C) -> Boolean?)? = null
    ): C {
        return bind(key.name, validatorMessage, validator)
    }

    /**
     * Bind a date control to the form.
     * @param key key identifier of the control
     * @param validatorMessage optional function returning validation message
     * @param validator optional validation function
     * @return the control itself
     */
    @Composable
    public open fun <C : DateFormControl> C.bind(
        key: KProperty1<K, LocalDate?>,
        validatorMessage: ((C) -> String?)? = null,
        validator: ((C) -> Boolean?)? = null
    ): C {
        return bind(key.name, validatorMessage, validator)
    }

    /**
     * Bind a datetime control to the form.
     * @param key key identifier of the control
     * @param validatorMessage optional function returning validation message
     * @param validator optional validation function
     * @return the control itself
     */
    @Composable
    public open fun <C : DateTimeFormControl> C.bind(
        key: KProperty1<K, LocalDateTime?>,
        validatorMessage: ((C) -> String?)? = null,
        validator: ((C) -> Boolean?)? = null
    ): C {
        return bind(key.name, validatorMessage, validator)
    }

    /**
     * Bind a time control to the form.
     * @param key key identifier of the control
     * @param validatorMessage optional function returning validation message
     * @param validator optional validation function
     * @return the control itself
     */
    @Composable
    public open fun <C : TimeFormControl> C.bind(
        key: KProperty1<K, LocalTime?>,
        validatorMessage: ((C) -> String?)? = null,
        validator: ((C) -> Boolean?)? = null
    ): C {
        return bind(key.name, validatorMessage, validator)
    }

    /**
     * Bind a files control to the form.
     * @param key key identifier of the control
     * @param validatorMessage optional function returning validation message
     * @param validator optional validation function
     * @return the control itself
     */
    @Composable
    public open fun <C : KFilesFormControl> C.bind(
        key: KProperty1<K, List<KFile>?>,
        validatorMessage: ((C) -> String?)? = null,
        validator: ((C) -> Boolean?)? = null
    ): C {
        return bind(key.name, validatorMessage, validator)
    }

    /**
     * Unbind a control from the form.
     * @param key key identifier of the control
     */
    public open fun unbind(key: KProperty1<K, *>) {
        unbind(key.name)
    }

    /**
     * Unbind a control from the form with a dynamic key.
     * @param key key identifier of the control
     */
    public open fun unbind(key: String) {
        this.fields.remove(key)
        this.fieldsParams.remove(key)
    }

    /**
     * Invokes validator function and validates the form.
     * @param updateState whether to update the validation state flow
     * @return validation result
     */
    public fun validate(updateState: Boolean = true): Boolean {
        checkValidity()
        val fieldsValidations = fields.map { (key, control) ->
            @Suppress("UNCHECKED_CAST")
            val fieldsParams = (fieldsParams[key] as FieldParams<*, FormControl<*>>)
            val required = control.required ?: false
            val isEmptyWhenRequired = (control.getValue() == null || control.value == false)
                    && control.visible && required
            val requiredMessage = if (isEmptyWhenRequired) "Value is required" else null
            val isInvalid = control.visible && !(fieldsParams.validator?.invoke(control) ?: true)
            val invalidMessage = if (isInvalid) {
                fieldsParams.validatorMessage?.invoke(control) ?: "Invalid value"
            } else {
                null
            }
            getControl(key)?.customValidity = invalidMessage ?: requiredMessage
            val validMessage = if (!isInvalid) {
                fieldsParams.validatorMessage?.invoke(control)
            } else {
                null
            }
            val fieldValidation = FieldValidation(
                isEmptyWhenRequired,
                isInvalid,
                validMessage,
                invalidMessage
            )
            key to fieldValidation
        }.toMap()
        val isInvalid = !(validator?.invoke(this) ?: true)
        val invalidMessage = if (isInvalid) {
            validatorMessage?.invoke(this) ?: "Invalid form data"
        } else {
            null
        }
        val validMessage = if (!isInvalid) {
            validatorMessage?.invoke(this)
        } else {
            null
        }
        val hasInvalidField = fieldsValidations.map { it.value }.find { it.isInvalid || it.isEmptyWhenRequired } != null
        val validation = Validation<K>(true, isInvalid || hasInvalidField, validMessage, invalidMessage, fieldsValidations)
        if (updateState) _mutableValidationStateFlow.value = validation
        return !validation.isInvalid
    }

    /**
     * Clear validation information.
     */
    public open fun clearValidation() {
        _mutableValidationStateFlow.value = Validation()
    }

    /**
     * Returns a control of given key.
     * @param key key identifier of the control
     * @return selected control
     */
    public open fun getControl(key: KProperty1<K, *>): FormControl<*>? {
        return getControl(key.name)
    }

    /**
     * Returns a control of given dynamic key.
     * @param key key identifier of the control
     * @return selected control
     */
    public open fun getControl(key: String): FormControl<*>? {
        return fields[key]
    }

    /**
     * Returns a value of the control of given key.
     * @param key key identifier of the control
     * @return value of the control
     */
    public operator fun <V> get(key: KProperty1<K, V>): V? {
        return get(key.name)
    }

    /**
     * Returns a value of the control of given dynamic key.
     * @param key key identifier of the control
     * @return value of the control
     */
    public operator fun <V> get(key: String): V? {
        return getControl(key)?.getValue()?.cast<V>()
    }

    /**
     * Returns the first control added to the form.
     * @return the first control
     */
    public open fun getFirstFormControl(): FormControl<*>? {
        return this.fields.firstNotNullOfOrNull { it.value }
    }

    /**
     * Submit the html form.
     */
    public open fun submit() {
        if (renderConfig.isDom) element.submit()
    }

    /**
     * Reset the html form.
     */
    public open fun reset() {
        if (renderConfig.isDom) element.reset()
    }

    /**
     * Check validity of the html form.
     */
    public open fun checkValidity(): Boolean {
        return if (renderConfig.isDom) element.checkValidity() else false
    }

    /**
     * Report validity of the html form.
     */
    public open fun reportValidity(): Boolean {
        return if (renderConfig.isDom) element.reportValidity() else false
    }

    /**
     * Focuses the first form control on the form.
     */
    override fun focus() {
        getFirstFormControl()?.focus()
    }

    override fun buildHtmlPropertyList(propertyListBuilder: PropertyListBuilder) {
        super.buildHtmlPropertyList(propertyListBuilder)
        propertyListBuilder.add(
            ::method,
            ::action,
            ::enctype,
            ::name,
            ::target,
            ::novalidate,
            ::autocomplete,
        )
    }

    public companion object {

        /**
         * Creates a [Form] component with a data class model.
         */
        public inline fun <reified K : Any> create(
            method: FormMethod? = null, action: String? = null, enctype: FormEnctype? = null,
            novalidate: Boolean? = null,
            customSerializers: Map<KClass<*>, KSerializer<*>>? = null,
            className: String? = null,
            renderConfig: RenderConfig = DefaultRenderConfig(),
        ): Form<K> {
            return Form(method, action, enctype, novalidate, serializer(), customSerializers, className, renderConfig)
        }
    }

}

/**
 * Creates a [Form] component with a data class model.
 *
 * @param initialData the initial data model
 * @param method the method attribute of the generated HTML form element
 * @param action the action attribute of the generated HTML form element
 * @param enctype the enctype attribute of the generated HTML form element
 * @param novalidate the novalidate attribute of the generated HTML form element
 * @param customSerializers custom serializers for the data model
 * @param className the CSS class name
 * @param content the content of the component
 * @return the [Form] component
 */
@Composable
public inline fun <reified K : Any> ComponentBase.form(
    initialData: K? = null,
    method: FormMethod? = null, action: String? = null, enctype: FormEnctype? = null,
    novalidate: Boolean? = null,
    customSerializers: Map<KClass<*>, KSerializer<*>>? = null,
    className: String? = null,
    content: @Composable Form<K>.() -> Unit = {}
): Form<K> {
    val component =
        remember { Form.create<K>(method, action, enctype, novalidate, customSerializers, className, renderConfig) }
    DisposableEffect(component.componentId) {
        component.onInsert()
        if (initialData != null) {
            component.setDataFromCompose(initialData)
        }
        onDispose {
            component.onRemove()
        }
    }
    ComponentNode(component, {
        set(initialData) { if (it != null) setDataFromCompose(it) }
        set(method) { updateProperty(Form<K>::method, it) }
        set(action) { updateProperty(Form<K>::action, it) }
        set(enctype) { updateProperty(Form<K>::enctype, it) }
        set(novalidate) { updateProperty(Form<K>::novalidate, it) }
        set(className) { updateProperty(Form<K>::className, it) }
    }, content)
    return component
}

/**
 * Creates a [Form] component with map model.
 *
 * @param initialData the initial data model
 * @param method the method attribute of the generated HTML form element
 * @param action the action attribute of the generated HTML form element
 * @param enctype the enctype attribute of the generated HTML form element
 * @param novalidate the novalidate attribute of the generated HTML form element
 * @param customSerializers custom serializers for the data model
 * @param className the CSS class name
 * @param content the content of the component
 * @return the [Form] component
 */
@Composable
public fun ComponentBase.form(
    initialData: Map<String, Any?>? = null,
    method: FormMethod? = null, action: String? = null, enctype: FormEnctype? = null,
    novalidate: Boolean? = null,
    customSerializers: Map<KClass<*>, KSerializer<*>>? = null,
    className: String? = null,
    content: @Composable Form<Map<String, Any?>>.() -> Unit = {}
): Form<Map<String, Any?>> {
    val component =
        remember {
            Form<Map<String, Any?>>(
                method,
                action,
                enctype,
                novalidate,
                null,
                customSerializers,
                className,
                renderConfig
            )
        }
    DisposableEffect(component.componentId) {
        component.onInsert()
        if (initialData != null) {
            component.setDataFromCompose(initialData)
        }
        onDispose {
            component.onRemove()
        }
    }
    ComponentNode(component, {
        set(initialData) { if (it != null) setDataFromCompose(it) }
        set(method) { updateProperty(Form<Map<String, Any?>>::method, it) }
        set(action) { updateProperty(Form<Map<String, Any?>>::action, it) }
        set(enctype) { updateProperty(Form<Map<String, Any?>>::enctype, it) }
        set(novalidate) { updateProperty(Form<Map<String, Any?>>::novalidate, it) }
        set(className) { updateProperty(Form<Map<String, Any?>>::className, it) }
    }, content)
    return component
}
