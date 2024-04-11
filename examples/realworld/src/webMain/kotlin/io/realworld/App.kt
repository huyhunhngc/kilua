/*
 * Copyright (c) 2024 Robert Jaros
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

package io.realworld

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.kilua.Application
import dev.kilua.RsupProgressModule
import dev.kilua.compose.root
import dev.kilua.html.header
import dev.kilua.html.main
import dev.kilua.ssr.SsrRouter
import dev.kilua.startApplication
import dev.kilua.utils.decodeURIComponent
import io.realworld.layout.articles.article
import io.realworld.layout.homePage
import io.realworld.layout.profilePage
import io.realworld.layout.shared.footer
import io.realworld.layout.shared.headerNav
import io.realworld.layout.users.editorPage
import io.realworld.layout.users.loginPage
import io.realworld.layout.users.registerPage
import io.realworld.layout.users.settingsPage

class App : Application() {

    val conduitManager = ConduitManager()

    override fun start() {

        conduitManager.initialize()

        root("root") {
            val state by conduitManager.state.collectAsState()

            SsrRouter(
                initPath = View.HOME.url,
                !state.appLoading,
                { conduitManager.serializeStateForSsr() }
            ) { done ->
                header {
                    headerNav(state)
                }
                main {
                    route(View.HOME.url) {
                        homePage(state, conduitManager)
                        if (!state.appLoading) {
                            LaunchedEffect(parameters?.raw) {
                                conduitManager.homePage(done)
                            }
                        }
                    }
                    route(View.ARTICLE.url) {
                        string { slug ->
                            if (slug == state.article?.slug) article(state, conduitManager)
                            LaunchedEffect(slug + parameters?.raw?.let { "?$it" }) {
                                conduitManager.showArticle(slug, done)
                            }
                        }
                    }
                    route(View.PROFILE.url) {
                        string {
                            val username = decodeURIComponent(it)
                            route("/favorites") {
                                if (state.profile?.username == username) profilePage(state, conduitManager)
                                LaunchedEffect(username + parameters?.raw?.let { "?$it" }) {
                                    conduitManager.showProfile(username, true, done)
                                }
                            }
                            noMatch {
                                if (state.profile?.username == username) profilePage(state, conduitManager)
                                LaunchedEffect(username + parameters?.raw?.let { "?$it" }) {
                                    conduitManager.showProfile(username, false, done)
                                }
                            }
                        }
                    }
                    route(View.LOGIN.url) {
                        loginPage(state, conduitManager)
                        LaunchedEffect(parameters?.raw) {
                            conduitManager.loginPage()
                            done()
                        }
                    }
                    route(View.REGISTER.url) {
                        registerPage(state, conduitManager)
                        LaunchedEffect(parameters?.raw) {
                            conduitManager.registerPage()
                            done()
                        }
                    }
                    route(View.EDITOR.url) {
                        string { slug ->
                            if (slug == state.editedArticle?.slug) editorPage(state, conduitManager)
                            LaunchedEffect(slug + parameters?.raw?.let { "?$it" }) {
                                conduitManager.editorPage(slug)
                                done()
                            }
                        }
                        noMatch {
                            editorPage(state, conduitManager)
                            LaunchedEffect(parameters?.raw) {
                                conduitManager.editorPage()
                                done()
                            }
                        }
                    }
                    route(View.SETTINGS.url) {
                        settingsPage(state, conduitManager)
                        LaunchedEffect(parameters?.raw) {
                            conduitManager.settingsPage()
                            done()
                        }
                    }
                }
                footer()
            }
        }
    }
}

fun main() {
    startApplication(::App, null, RsupProgressModule)
}
