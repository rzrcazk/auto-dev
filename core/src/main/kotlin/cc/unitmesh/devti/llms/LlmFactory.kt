package cc.unitmesh.devti.llms

import cc.unitmesh.devti.llm2.model.LlmConfig
import cc.unitmesh.devti.llm2.model.ModelType
import cc.unitmesh.devti.llms.custom.CustomLLMProvider
import cc.unitmesh.devti.settings.coder.AutoDevCoderSettingService
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

object LlmFactory {
    fun create(project: Project): LLMProvider {
        return CustomLLMProvider(project)
    }

    /**
     * New LLMProvider with custom config, for easy to migration old llm provider
     */
    fun create(project: Project, modelType: ModelType): LLMProvider {
        val llmConfigs = LlmConfig.load(modelType)
        val llmConfig = llmConfigs.firstOrNull() ?: LlmConfig.default()
        return CustomLLMProvider(project, llmConfig)
    }

    fun createForInlayCodeComplete(project: Project): LLMProvider {
        if(project.service<AutoDevCoderSettingService>().state.useCustomAIEngineWhenInlayCodeComplete) {
            val llmConfigs = LlmConfig.load(ModelType.Completion)
            val llmConfig = llmConfigs.firstOrNull() ?: LlmConfig.default()
            return CustomLLMProvider(project, llmConfig)
        }

        return create(project);
    }

}
