package cc.unitmesh.devti.agent

import cc.unitmesh.devti.AutoDevBundle
import cc.unitmesh.devti.agent.model.CustomAgentConfig
import cc.unitmesh.devti.agent.model.CustomAgentState
import cc.unitmesh.devti.agent.model.CustomAgentResponseAction
import cc.unitmesh.devti.gui.chat.ChatCodingPanel
import cc.unitmesh.devti.gui.chat.ChatRole
import cc.unitmesh.devti.llms.LLMProvider
import cc.unitmesh.devti.provider.ContextPrompter
import cc.unitmesh.devti.provider.devins.LanguagePromptProcessor
import cc.unitmesh.devti.provider.devins.CustomAgentContext
import cc.unitmesh.devti.util.LLMCoroutineScope
import cc.unitmesh.devti.util.parser.Code
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

@Service(Service.Level.PROJECT)
class CustomAgentChatProcessor(val project: Project) {
    private val customAgentExecutor = project.service<CustomAgentExecutor>()
    private val logger = logger<CustomAgentChatProcessor>()

    fun handleChat(prompter: ContextPrompter, ui: ChatCodingPanel, llmProvider: LLMProvider) {
        val originPrompt = prompter.requestPrompt()
        ui.addMessage(originPrompt, true, originPrompt)

        val request = originPrompt.trim()
        val selectedAgent: CustomAgentConfig = ui.getSelectedCustomAgent()

        selectedAgent.state = CustomAgentState.HANDLING

        val response: Flow<String>? = customAgentExecutor.execute(request, selectedAgent)
        if (response == null) {
            logger.error("error for custom agent: $selectedAgent with request: $request")
            return
        }

        selectedAgent.state = CustomAgentState.FINISHED

        var devInCode: String? = ""
        when (selectedAgent.responseAction) {
            CustomAgentResponseAction.Direct -> {
                val message = ui.addMessage("loading", false, "")
                val sb = StringBuilder()
                runBlocking {
                    val result = ui.updateMessage(response)
                    sb.append(result)
                }

                val content = sb.toString().removeSurrounding("\"")
                llmProvider.appendLocalMessage(content, ChatRole.Assistant)
                message.reRenderAssistantOutput()

                val code = Code.parse(content)
                if (code.language.displayName == "DevIn") {
                    devInCode = code.text
                }

                ui.hiddenProgressBar()
                ui.updateUI()
            }

            CustomAgentResponseAction.Stream -> {
                ui.addMessage(AutoDevBundle.message("autodev.loading"))
                var msg = ""
                LLMCoroutineScope.scope(project).launch {
                    msg = ui.updateMessage(response)
                }

                llmProvider.appendLocalMessage(msg, ChatRole.Assistant)
                ui.hiddenProgressBar()
                ui.updateUI()

                val code = Code.parse(msg)
                if (code.language.displayName == "DevIn") {
                    devInCode = code.text
                }
            }

            CustomAgentResponseAction.TextChunk -> {
                val sb = StringBuilder()
                runBlocking {
                    response.collect {
                        sb.append(it)
                    }
                }

                val content = sb.toString()
                llmProvider.appendLocalMessage(content, ChatRole.Assistant)
                ui.removeLastMessage()
                ui.moveCursorToStart()
                ui.setInput(content)
                ui.hiddenProgressBar()
            }

            CustomAgentResponseAction.Flow -> {
                logger.error("will not support flow response for now")
            }

            CustomAgentResponseAction.WebView -> {
                val sb = StringBuilder()
                runBlocking {
                    response.collect {
                        sb.append(it)
                    }
                }

                val content = sb.toString()
                llmProvider.appendLocalMessage(content, ChatRole.Assistant)

                ui.appendWebView(content, project)
                ui.hiddenProgressBar()
            }

            CustomAgentResponseAction.DevIns -> {
                ui.addMessage(AutoDevBundle.message("autodev.loading"))
                var msg = ""
                LLMCoroutineScope.scope(project).launch {
                    msg = ui.updateMessage(response)
                }

                llmProvider.appendLocalMessage(msg, ChatRole.Assistant)
                ui.hiddenProgressBar()
                ui.updateUI()

                devInCode = msg
            }
        }

        if (!devInCode.isNullOrEmpty()) {
            LanguagePromptProcessor.instance("DevIn").forEach {
                it.execute(project, CustomAgentContext(selectedAgent, devInCode))
            }
        }
    }
}
