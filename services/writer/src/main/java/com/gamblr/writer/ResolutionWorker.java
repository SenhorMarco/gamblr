package com.gamblr.writer;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="gamblr.resolution.worker-enabled",havingValue="true",matchIfMissing=true)
class ResolutionWorker {
    private static final Logger log=LoggerFactory.getLogger(ResolutionWorker.class);
    private final ResolutionService service;
    private UUID after;
    ResolutionWorker(ResolutionService service) {this.service=service;}
    @Scheduled(fixedDelayString="${gamblr.resolution.poll-ms:5000}",initialDelayString="${gamblr.resolution.poll-ms:5000}")
    void tick() {
        try {
            var ids=service.batch(after);
            if(ids.isEmpty()){after=null;return;}
            for(UUID id:ids) {
                try {service.process(id);}catch(RuntimeException error){log.warn("Não foi possível concluir a aposta {}. O processamento será repetido.",id);}
                after=id;
            }
            if(ids.size()<100)after=null;
        } catch(RuntimeException error){after=null;log.warn("Processamento de apostas indisponível. Confira conexão e migração V008; uma nova tentativa será feita.");}
    }
}
