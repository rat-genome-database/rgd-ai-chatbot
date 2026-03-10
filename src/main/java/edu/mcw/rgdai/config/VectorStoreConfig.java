package edu.mcw.rgdai.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import edu.mcw.rgdai.repository.DocumentEmbeddingRepository;
import edu.mcw.rgdai.repository.DocumentEmbeddingOpenAIRepository;
import edu.mcw.rgdai.vectorstore.PostgresVectorStore;
import edu.mcw.rgdai.vectorstore.PostgresVectorStoreOpenAI;

import jakarta.annotation.PostConstruct;
import java.util.Map;

@Configuration
public class VectorStoreConfig {

    @Autowired
    private ApplicationContext context;

    @PostConstruct
    public void debugBeans() {
        System.out.println("=== ALL EMBEDDING MODEL BEANS ===");
        Map<String, EmbeddingModel> embeddingModels = context.getBeansOfType(EmbeddingModel.class);
        for (Map.Entry<String, EmbeddingModel> entry : embeddingModels.entrySet()) {
            System.out.println("Bean name: '" + entry.getKey() + "', Class: " + entry.getValue().getClass().getSimpleName());
        }
        System.out.println("===================================");
    }

    @Bean
    @Primary
    @Qualifier("ollamaVectorStore")
    VectorStore ollamaVectorStore(@Qualifier("ollamaEmbeddingModel") EmbeddingModel ollamaEmbeddingModel,
                                  DocumentEmbeddingRepository repository) {
        System.out.println("Creating Ollama VectorStore");
        return new PostgresVectorStore(repository, ollamaEmbeddingModel);
    }

    @Bean
    @Qualifier("openaiVectorStore")
    VectorStore openaiVectorStore(DocumentEmbeddingOpenAIRepository repository) {
        System.out.println("Looking for OpenAI embedding model...");

        // Get all embedding models and find the OpenAI one
        Map<String, EmbeddingModel> embeddingModels = context.getBeansOfType(EmbeddingModel.class);
        EmbeddingModel openAiModel = null;

        for (Map.Entry<String, EmbeddingModel> entry : embeddingModels.entrySet()) {
            String beanName = entry.getKey();
            EmbeddingModel model = entry.getValue();
            System.out.println("Checking bean: '" + beanName + "', Class: " + model.getClass().getSimpleName());

            // Look for OpenAI embedding model by class name
            if (model.getClass().getSimpleName().toLowerCase().contains("openai")) {
                openAiModel = model;
                System.out.println("Found OpenAI embedding model: " + beanName);
                break;
            }
        }

        if (openAiModel == null) {
            throw new RuntimeException("Could not find OpenAI embedding model! Available beans: " + embeddingModels.keySet());
        }

        return new PostgresVectorStoreOpenAI(repository, openAiModel);
    }
}
//package edu.mcw.rgdai.config;
//
//        import org.springframework.ai.embedding.EmbeddingModel;
//        import org.springframework.ai.vectorstore.VectorStore;
//        import org.springframework.beans.factory.annotation.Qualifier;
//        import org.springframework.context.annotation.Bean;
//        import org.springframework.context.annotation.Configuration;
//        import edu.mcw.rgdai.repository.DocumentEmbeddingRepository;
//        import edu.mcw.rgdai.vectorstore.PostgresVectorStore;
//
//@Configuration
//public class VectorStoreConfig {
//    @Bean
//    VectorStore vectorStore(@Qualifier("ollamaEmbeddingModel")EmbeddingModel embeddingModel, DocumentEmbeddingRepository repository) {
//        return new PostgresVectorStore(repository, embeddingModel);
//    }
//}

