Requisitos do Gamblr

O Gamblr é uma rede social em que usuários conversam em chats e fazem previsões sobre eventos. As apostas de grupo usam pontos virtuais e têm ranking próprio. Neste documento, aposta é a proposta publicada no chat e palpite é a escolha confirmada por um participante.

## Acompanhamento da implementação

Revisão em 14/11/2026, após atualização com `origin/main`.
**Implementado** indica funcionalidade presente no código; **Parcial** indica
uma limitação descrita na anotação. As marcações não representam uma nova
validação ponta a ponta do ambiente nem alteram as regras abaixo.

As funcionalidades de administração e crédito diário exigem a migração
[V009](../database/migrations/V009__group_administration.sql), além das anteriores.
O crédito diário e a apuração automática dependem do serviço Java em execução;
os workers retomam os processamentos pendentes. Veja [administração](administracao.md),
[resultados](resultados.md) e [execução local](execucao-local.md).

Contas e comunicação

**Status: Implementado** — Cadastro e login disponíveis na interface com Supabase Auth (`frontend/static/auth.js`).

RF-30 Cadastro e login — O site deve permitir que usuários criem uma conta e façam login.

**Status: Implementado** — Pedidos, aceite e autorização do chat privado implementados em `ChatService` e `frontend/static/chat.js`.

RF-16 Amizades e chat privado — Um usuário pode enviar um pedido de amizade a outro. O chat privado entre os dois fica disponível após o destinatário aceitar o pedido.

**Status: Implementado** — Criação, chat e restrição do histórico pela entrada implementados; apostas abertas acessíveis aos novos membros.

RF-31 Criação e chat de grupo — Um usuário pode criar um grupo e se torna seu dono. Os integrantes podem conversar em um chat compartilhado pelo grupo. Usuários que entram no grupo não podem consultar o histórico anterior de mensagens, mas podem visualizar as apostas que ainda estão abertas.

**Status: Implementado** — Chats privados não oferecem publicação ou participação em apostas.

RF-32 Apostas em chats privados — Não haverá apostas em chats privados.

Entrada e administração dos grupos

**Status: Implementado** — Convites, aceite/recusa, solicitações e política de convites implementados em `GroupService` e `frontend/static/groups.js` (V009).

RF-17 Entrada e convites — Usuários podem entrar em um grupo por convite. Grupos públicos podem permitir entrada imediata ou exigir solicitação. O dono configura se os convites podem ser enviados por todos os membros ou se ficam restritos aos moderadores.

**Status: Implementado** — Aprovação, remoção por hierarquia e exclusão de mensagens/apostas implementadas; exclusão de apostas preserva o histórico financeiro (V009).

RF-18 Solicitações, remoção de membros e mensagens — O dono e os moderadores podem aprovar solicitações de entrada e apagar mensagens do chat, inclusive mensagens de outros membros. O dono pode remover membros comuns e moderadores e apagar apostas. Um moderador pode remover apenas membros comuns e não pode apagar apostas.

Ao apagar uma aposta pendente, o sistema a cancela e devolve integralmente os pontos reservados. Se a aposta já estiver concluída, apenas a oculta, sem desfazer pagamentos. O histórico financeiro é preservado. Decisão confirmada para a exclusão de apostas pelo dono.

**Status: Implementado** — Promoção e destituição restritas ao dono em `GroupService` (V009).

RF-29 Gestão de moderadores — Somente o dono pode nomear ou destituir moderadores.

**Status: Implementado** — Transferência e sucessão por papel e primeira entrada implementadas, com mudança do responsável pelas contestações (V009).

RF-27 Transferência e sucessão — O dono pode transferir a propriedade para outro membro. A transferência é imediata e dispensa aceite. O antigo dono passa a ser moderador. Se o dono sair sem nomear um sucessor, o sistema transfere a propriedade ao moderador atual com a primeira entrada mais antiga. Se não houver moderadores, assume o membro atual com a primeira entrada mais antiga. A primeira data de entrada é preservada em caso de retorno. O novo dono assume as contestações pendentes.

**Status: Implementado** — Saída do último dono realiza exclusão lógica, preservando registros financeiros (V009).

RF-28 Exclusão do grupo — O sistema exclui o grupo quando seu dono sai e era o último membro.

Pontos e ranking

**Status: Implementado** — Carteira por grupo e crédito inicial único implementados nos fluxos de entrada.

RF-01 Saldo inicial — Cada usuário tem um saldo independente em cada grupo. Na primeira entrada, recebe a quantidade de pontos definida pelo dono do grupo. O sistema concede esse crédito uma única vez por usuário e por grupo.

**Status: Implementado** — Configuração, agendamento à meia-noite de Brasília e recuperação idempotente implementados em `DailyCreditService` e `DailyCreditWorker` (V009).

RF-02 Crédito diário — O dono define uma quantidade fixa de pontos por dia para o grupo. À meia-noite no horário de Brasília (America/Sao\_Paulo), o sistema credita essa quantidade a cada usuário que for membro naquele momento, uma única vez por dia e por grupo.

**Status: Implementado** — Saída preserva carteira; retorno não repete crédito inicial. Administração integra esses fluxos (V009).

RF-03 Saldo no retorno — O sistema preserva o saldo do usuário que sai ou é removido. Se ele retornar, recupera o saldo atualizado pelas movimentações das apostas em que participou, sem novo crédito inicial.

**Status: Implementado** — Pontos virtuais sem compra ou saque. Prêmios internos permanecem uma possibilidade futura, fora do escopo implementado.

RF-04 Uso dos pontos — Os pontos são exclusivamente virtuais, sem compra ou saque. Prêmios para resgate dentro da própria plataforma são uma possibilidade futura e permanecem em aberto.

**Status: Implementado** — Saída/remoção preserva palpites e carteiras e processa os efeitos sobre autoria e contestações (V009).

RF-19 Palpites após saída ou remoção — O palpite confirmado continua válido quando seu participante sai ou é removido. Os pontos continuam reservados até a conclusão da aposta. O sistema registra ganhos e devoluções no saldo daquele grupo mesmo enquanto o participante estiver fora. A saída ou remoção do autor segue o RF-10, e a retirada de contestações segue o RF-11.

**Status: Implementado** — Ranking por saldo disponível e desempate por criação da conta implementados na view e no reader.

RF-20 Ranking — O ranking de cada grupo ordena seus membros pelo saldo disponível, do maior para o menor. Pontos reservados ficam fora desse saldo. Em caso de empate, aparece primeiro quem criou a conta no Gamblr há mais tempo.

Publicação e participação nas apostas de grupo

**Status: Implementado** — Publicação binária/múltipla escolha implementada; evento com acesso à aposta no chat adicionado pela V009.

RF-23 Publicação da aposta — O membro pode propor uma aposta no chat do grupo. Ao publicar, informa a pergunta, o tipo de aposta, as opções de resposta, o horário de término e o valor mínimo em pontos por palpite. A aposta binária oferece Sim e Não. A de múltipla escolha oferece as opções propostas pelo autor. O critério de verificação do resultado é opcional.

**Status: Implementado** — Não há operações de edição de apostas ou opções publicadas.

RF-25 Dados da aposta publicada — Os dados da aposta ficam bloqueados para edição a partir da publicação.

**Status: Implementado** — Servidor verifica fechamento aos 90% antes de confirmar o palpite.

RF-06 Horário de fechamento — A duração da aposta vai da publicação ao horário de término escolhido pelo autor. A aposta deixa de aceitar palpites quando completa 90% dessa duração. O servidor rejeita novos palpites a partir desse instante.

**Status: Implementado** — Cancelamento no período inicial e devolução transacional implementados em `ResolutionService`.

RF-24 Cancelamento pelo autor — O autor pode cancelar a aposta durante os primeiros 10% da duração, inclusive se já houver palpites. O sistema devolve integralmente os pontos conforme o RF-12.

**Status: Implementado** — Servidor valida participação atual, prazo e impedimento do autor.

RF-15 Participação — Qualquer membro atual pode apostar enquanto os palpites estiverem abertos, exceto o autor da proposta. Um usuário que entrar no grupo após a publicação pode participar se o prazo de palpites ainda estiver aberto.

**Status: Implementado** — Unicidade no banco e ausência de operações de alteração/retirada de palpites confirmados.

RF-08 Palpite único e definitivo — Cada participante pode confirmar um único palpite, escolhendo uma única opção. Após a confirmação, não pode mudar a escolha, alterar o valor ou retirar o palpite.

**Status: Implementado** — Validação de saldo/mínimo e reserva na mesma transação, com bloqueios e proteção contra concorrência.

RF-09 Valor e reserva de pontos — O valor do palpite deve atingir o mínimo definido pelo autor e caber no saldo disponível do participante. O sistema registra o palpite e reserva os pontos na mesma operação. A reserva reduz imediatamente o saldo disponível e impede o uso dos mesmos pontos em outras apostas.

Visibilidade das apostas de grupo

**Status: Parcial (Ferreira fazendo)** — Contagem e odds disponíveis a todos os membros; interface consulta a cada 5 segundos enquanto visível. Atualização contínua por eventos não implementada.

RF-13 Contagem e odds em tempo real — Todos os membros podem acompanhar, em tempo real, a contagem de votos e a odd de cada opção em todas as apostas que ainda aceitam palpites, inclusive quando não apostaram nelas.

**Status: Implementado** — APIs expõem apenas o palpite próprio e agregados autorizados do grupo.

RF-14 Privacidade dos palpites — Cada participante pode consultar somente a própria escolha e o valor que apostou. Os dados compartilhados com o grupo são agregados por opção.

**Status: Implementado** — View de odds calcula total de votos/votos da opção; interface exibe “—” sem votos.

RF-05 Cálculo da odd decimal — Para uma opção com votos, divide-se o total de votos da aposta pelo número de votos daquela opção. Por exemplo, 2 votos de um total de 5 produzem odd 2,50. Opções sem votos exibem “—”. O pagamento segue o RF-07.

Apuração e contestação das apostas de grupo

**Status: Implementado** — Configuração editável pelo dono e cópia dos prazos na publicação implementadas (V009).

RF-26 Configuração dos prazos — O dono configura os prazos de apuração e de contestação para o grupo. O sistema registra os prazos vigentes no momento da publicação. Mudanças na configuração valem apenas para apostas publicadas depois da alteração.

**Status: Implementado** — Publicação, cancelamento por prazo e efeitos da saída do autor implementados em `ResolutionService` e worker; saída integrada pela V009.

RF-10 Publicação do resultado e cancelamento automático — Após o horário de término, o autor pode publicar a opção vencedora até o fim do prazo de apuração, contado a partir do término. Se o prazo expirar sem resultado, o sistema cancela a aposta. A saída ou remoção do autor antes da publicação do resultado também cancela a aposta. Após a publicação do resultado, a aposta segue para conclusão mesmo que o autor saia ou seja removido.

**Status: Implementado** — Reserva, contestação, retirada por saída e conclusão conforme prazo implementadas; saída integrada pela V009.

RF-11 Contestação e reserva dos pontos — O prazo de contestação começa com a publicação do resultado. Durante esse prazo, um usuário pode contestar se tiver apostado e ainda for membro do grupo. Os pontos ficam reservados até o fim do prazo. Sem contestação pendente, o sistema apura pelo resultado publicado. Se houver contestação pendente, mantém os pontos reservados até a decisão do dono. Se o participante sair ou for removido antes da decisão, sua contestação é removida e seu palpite continua válido. Se a última contestação for removida antes do fim do prazo, o sistema aguarda o prazo terminar. Se o prazo já tiver terminado, o sistema apura imediatamente pelo resultado publicado.

**Status: Implementado** — Decisões do dono e preservação do prazo para novas contestações implementadas em `ResolutionService`.

RF-22 Decisão sobre a contestação — O dono do grupo pode decidir a qualquer momento, sem prazo limite, inclusive quando é autor da aposta ou registrou um palpite. Ele pode manter o resultado publicado, escolher outra opção vencedora ou cancelar a aposta. Se decidir antes do fim do prazo de contestação, a decisão fica registrada, mas o prazo continua até o fim e os participantes ainda podem contestar durante esse período. Ao fim do prazo, sem contestações pendentes, o sistema apura pelo resultado final. O cancelamento segue o RF-12.

Distribuição dos pontos

**Status: Implementado** — Rateio proporcional, arredondamento para baixo e tratamento de vencedores com zero pontos implementados em `ResolutionService`.

RF-07 Pagamento aos vencedores — Cada vencedor recupera integralmente os pontos que apostou e recebe uma parcela dos pontos perdidos. Para calcular a parcela, multiplica-se o total de pontos perdidos pelo valor apostado pelo vencedor e divide-se pelo total de pontos apostados pelos vencedores. O sistema arredonda a parcela individual para baixo e deixa a sobra sem distribuição. A odd decimal é calculada separadamente, pela contagem de votos.

Se a opção vencedora tiver apenas palpites de zero pontos, a aposta é encerrada sem distribuir os pontos perdidos. Os palpites vencedores recebem zero e os pontos dos perdedores são descontados definitivamente. Decisão confirmada para evitar divisão por zero.

**Status: Implementado** — Devolução integral às carteiras, inclusive de ex-integrantes, implementada na conclusão transacional.

RF-12 Devolução por cancelamento — Ao cancelar uma aposta, o sistema devolve a cada participante a quantidade exata de pontos que ele apostou, inclusive se ele já tiver saído do grupo ou sido removido.

**Status: Implementado** — Conclusão sem vencedores consome reservas sem novo débito do saldo disponível.

RF-21 Resultado sem vencedores — Se ninguém tiver escolhido a opção vencedora no resultado final, o sistema encerra a aposta e desconta definitivamente os pontos reservados, sem novo débito no saldo disponível.

Requisitos técnicos

RT-01 Execução na rede local — Uma única máquina executa os serviços de backend. Os demais computadores acessam a aplicação e se conectam ao backend da máquina anfitriã pela mesma rede Wi-Fi.

RT-02 Serviços e endpoints — O backend inclui um serviço em Java com Spring Boot e um serviço em Python com Flask. A equipe define a divisão de responsabilidades. Cada serviço deve oferecer pelo menos dois endpoints.

RT-03 Banco de dados — O sistema usa o Supabase para persistir os dados da aplicação.

Critérios de aceitação

• Prazos. Uma aposta publicada às 10h com término às 20h pode ser cancelada pelo autor às 10h45. Às 11h01 o cancelamento pelo autor é rejeitado. Às 19h o servidor passa a rejeitar novos palpites.

• Pontos e odd. Ana aposta 10 pontos e Bruno aposta 30 na opção vencedora. Três pessoas apostam 80 pontos no total nas opções perdedoras. A odd da opção vencedora é 2,50 e os créditos finais são 30 pontos para Ana e 90 para Bruno.

• Contestação. Se a única contestação for removida às 21h45 e o prazo terminar às 22h, a aposta é apurada às 22h. Se a remoção ocorrer às 22h15, a aposta é apurada nesse momento. O palpite de quem contestou continua válido.

• Ranking. Com 100 pontos disponíveis, Ana fica à frente de Bruno, que tem 80\. Ao reservar 30 pontos numa aposta, Ana passa a ter 70 disponíveis e fica atrás de Bruno. Se a aposta for cancelada, ela recupera os 30 pontos e volta à frente, considerando apenas essas movimentações.

• Rede local. Dois usuários em computadores diferentes, conectados à mesma rede Wi-Fi, conseguem criar uma aposta e registrar um palpite, respectivamente. Ambos veem a contagem atualizada com os dois serviços executando somente na máquina anfitriã.

Decisões em aberto

• Prêmios internos. Tipos de prêmio, condições de resgate e disponibilidade futura.
