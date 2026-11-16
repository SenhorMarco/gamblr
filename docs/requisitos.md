# Requisitos do Gamblr

O Gamblr é uma rede social em que usuários conversam em chats e fazem previsões sobre eventos. As apostas de grupo usam pontos virtuais e têm ranking próprio. Neste documento, aposta é a proposta publicada no chat e palpite é a escolha confirmada por um participante.

## Condições de execução

O código atual requer todas as migrações V001–V011. Crédito diário e apuração
automática dependem do Java em execução; os workers retomam processamentos
pendentes. Consulte [administração](administracao.md), [resultados](resultados.md)
e [execução local](execucao-local.md).

## Contas e comunicação

RF-30 Cadastro e login — O site deve permitir que usuários criem uma conta e façam login.

RF-16 Amizades e chat privado — Um usuário pode enviar um pedido de amizade a outro. O chat privado entre os dois fica disponível após o destinatário aceitar o pedido.

RF-31 Criação e chat de grupo — Um usuário pode criar um grupo e se torna seu dono. Os integrantes podem conversar em um chat compartilhado pelo grupo. Usuários que entram no grupo não podem consultar o histórico anterior de mensagens, mas podem visualizar as apostas que ainda estão abertas.

RF-32 Apostas em chats privados — Não haverá apostas em chats privados.

## Entrada e administração dos grupos

RF-17 Entrada e convites — Usuários podem entrar em um grupo por convite. Grupos públicos podem permitir entrada imediata ou exigir solicitação. O dono configura se os convites podem ser enviados por todos os membros ou se ficam restritos aos moderadores.

RF-18 Solicitações, remoção de membros e mensagens — O dono e os moderadores podem aprovar solicitações de entrada e apagar mensagens do chat, inclusive mensagens de outros membros. O dono pode remover membros comuns e moderadores e apagar apostas. Um moderador pode remover apenas membros comuns e não pode apagar apostas.

Ao apagar uma aposta pendente, o sistema a cancela e devolve integralmente os pontos reservados. Se a aposta já estiver concluída, apenas a oculta, sem desfazer pagamentos. O histórico financeiro é preservado.

RF-29 Gestão de moderadores — Somente o dono pode nomear ou destituir moderadores.

RF-27 Transferência e sucessão — O dono pode transferir a propriedade para outro membro. A transferência é imediata e dispensa aceite. O antigo dono passa a ser moderador. Se o dono sair sem nomear um sucessor, o sistema transfere a propriedade ao moderador atual com a primeira entrada mais antiga. Se não houver moderadores, assume o membro atual com a primeira entrada mais antiga. A primeira data de entrada é preservada em caso de retorno. O novo dono assume as contestações pendentes.

RF-28 Exclusão do grupo — O sistema exclui o grupo quando seu dono sai e era o último membro.

## Pontos e ranking

RF-01 Saldo inicial — Cada usuário tem um saldo independente em cada grupo. Na primeira entrada, recebe a quantidade de pontos definida pelo dono do grupo. O sistema concede esse crédito uma única vez por usuário e por grupo.

RF-02 Crédito diário — O dono define uma quantidade fixa de pontos por dia para o grupo. À meia-noite no horário de Brasília (America/Sao\_Paulo), o sistema credita essa quantidade a cada usuário que for membro naquele momento, uma única vez por dia e por grupo.

RF-03 Saldo no retorno — O sistema preserva o saldo do usuário que sai ou é removido. Se ele retornar, recupera o saldo atualizado pelas movimentações das apostas em que participou, sem novo crédito inicial.

RF-04 Uso dos pontos — Os pontos são exclusivamente virtuais, sem compra ou saque. Os integrantes podem usar os pontos do próprio grupo para personalizar a cor do nome, o texto do título e a cor do título, conforme os RF-35 a RF-40.

RF-19 Palpites após saída ou remoção — O palpite confirmado continua válido quando seu participante sai ou é removido. Os pontos continuam reservados até a conclusão da aposta. O sistema registra ganhos e devoluções no saldo daquele grupo mesmo enquanto o participante estiver fora. A saída ou remoção do autor segue o RF-10, e a retirada de contestações segue o RF-11.

RF-20 Ranking — O ranking de cada grupo ordena seus membros pelo saldo disponível, do maior para o menor. Pontos reservados ficam fora desse saldo. Em caso de empate, aparece primeiro quem criou a conta no Gamblr há mais tempo.

## Publicação e participação nas apostas de grupo

RF-23 Publicação da aposta — O membro pode propor uma aposta no chat do grupo. Ao publicar, informa a pergunta, o tipo de aposta, as opções de resposta, o horário de término e o valor mínimo em pontos por palpite. A aposta binária oferece Sim e Não. A de múltipla escolha oferece as opções propostas pelo autor. O critério de verificação do resultado é opcional.

RF-25 Dados da aposta publicada — Os dados da aposta ficam bloqueados para edição a partir da publicação.

RF-06 Horário de fechamento — A duração da aposta vai da publicação ao horário de término escolhido pelo autor. A aposta deixa de aceitar palpites quando completa 90% dessa duração. O servidor rejeita novos palpites a partir desse instante.

RF-24 Cancelamento pelo autor — O autor pode cancelar a aposta durante os primeiros 10% da duração, inclusive se já houver palpites. O sistema devolve integralmente os pontos conforme o RF-12.

RF-15 Participação — Qualquer membro atual pode apostar enquanto os palpites estiverem abertos, exceto o autor da proposta. Um usuário que entrar no grupo após a publicação pode participar se o prazo de palpites ainda estiver aberto.

RF-08 Palpite único e definitivo — Cada participante pode confirmar um único palpite, escolhendo uma única opção. Após a confirmação, não pode mudar a escolha, alterar o valor ou retirar o palpite.

RF-09 Valor e reserva de pontos — O valor do palpite deve atingir o mínimo definido pelo autor e caber no saldo disponível do participante. O sistema registra o palpite e reserva os pontos na mesma operação. A reserva reduz imediatamente o saldo disponível e impede o uso dos mesmos pontos em outras apostas.

## Visibilidade das apostas de grupo

RF-13 Contagem e odds em tempo real — Todos os membros podem acompanhar, em tempo real, a contagem de votos e a odd de cada opção em todas as apostas que ainda aceitam palpites, inclusive quando não apostaram nelas.

RF-14 Privacidade dos palpites — Cada participante pode consultar somente a própria escolha e o valor que apostou. Os dados compartilhados com o grupo são agregados por opção.

RF-05 Cálculo da odd decimal — Para uma opção com pontos apostados, divide-se o total de pontos da aposta pelo total de pontos daquela opção. Por exemplo, 40 pontos de um total de 100 produzem odd 2,50. Opções sem pontos exibem “—”. O pagamento segue o RF-07.

## Apuração e contestação das apostas de grupo

RF-26 Configuração dos prazos — O dono configura os prazos de apuração e de contestação para o grupo. O sistema registra os prazos vigentes no momento da publicação. Mudanças na configuração valem apenas para apostas publicadas depois da alteração.

RF-10 Publicação do resultado e cancelamento automático — Após o horário de término, o autor pode publicar a opção vencedora até o fim do prazo de apuração, contado a partir do término. Se o prazo expirar sem resultado, o sistema cancela a aposta. A saída ou remoção do autor antes da publicação do resultado também cancela a aposta. Após a publicação do resultado, a aposta segue para conclusão mesmo que o autor saia ou seja removido.

RF-11 Contestação e reserva dos pontos — O prazo de contestação começa com a publicação do resultado. Durante esse prazo, um usuário pode contestar se tiver apostado e ainda for membro do grupo. Os pontos ficam reservados até o fim do prazo. Sem contestação pendente, o sistema apura pelo resultado publicado. Se houver contestação pendente, mantém os pontos reservados até a decisão do dono. Se o participante sair ou for removido antes da decisão, sua contestação é removida e seu palpite continua válido. Se a última contestação for removida antes do fim do prazo, o sistema aguarda o prazo terminar. Se o prazo já tiver terminado, o sistema apura imediatamente pelo resultado publicado.

RF-22 Decisão sobre a contestação — O dono do grupo pode decidir a qualquer momento, sem prazo limite, inclusive quando é autor da aposta ou registrou um palpite. Ele pode manter o resultado publicado, escolher outra opção vencedora ou cancelar a aposta. Se decidir antes do fim do prazo de contestação, a decisão fica registrada, mas o prazo continua até o fim e os participantes ainda podem contestar durante esse período. Ao fim do prazo, sem contestações pendentes, o sistema apura pelo resultado final. O cancelamento segue o RF-12.

## Distribuição dos pontos

RF-07 Pagamento aos vencedores — Cada vencedor recupera integralmente os pontos que apostou e recebe uma parcela dos pontos perdidos. Para calcular a parcela, multiplica-se o total de pontos perdidos pelo valor apostado pelo vencedor e divide-se pelo total de pontos apostados pelos vencedores. O sistema arredonda a parcela individual para baixo e deixa a sobra sem distribuição. A odd decimal é calculada pela proporção dos pontos apostados.

Se a opção vencedora tiver apenas palpites de zero pontos, a aposta é encerrada sem distribuir os pontos perdidos. Os palpites vencedores recebem zero e os pontos dos perdedores são descontados definitivamente.

RF-12 Devolução por cancelamento — Ao cancelar uma aposta, o sistema devolve a cada participante a quantidade exata de pontos que ele apostou, inclusive se ele já tiver saído do grupo ou sido removido.

RF-21 Resultado sem vencedores — Se ninguém tiver escolhido a opção vencedora no resultado final, o sistema encerra a aposta e desconta definitivamente os pontos reservados, sem novo débito no saldo disponível.

## Feed de apostas

RF-33 Feed de apostas dos grupos — O usuário pode acessar um Feed que reúne as apostas dos grupos dos quais é integrante atual, ordenadas da publicação mais recente para a mais antiga. Cada item identifica o grupo, a pergunta, a data de publicação e a situação. Ao selecionar Ver aposta, o sistema abre o grupo correspondente com a aposta selecionada. A listagem informa quando não há apostas ou quando algum grupo não pôde ser carregado.

RF-34 Filtros do Feed — O usuário pode combinar filtros por situação (todas, ativas, aceitando palpites, aguardando resultado, resultado publicado, concluídas ou canceladas), um ou mais grupos dos quais participa, intervalo de datas de publicação e texto da pergunta. Sem seleção de grupos, o Feed considera todos os seus grupos. O intervalo inclui os dias inicial e final no horário local do usuário; datas invertidas exibem erro. Os resultados preservam a ordem de publicação mais recente. O usuário pode limpar todos os filtros e consultar a quantidade de apostas encontradas. A interface informa quando nenhuma aposta corresponde aos filtros. Ativas inclui apostas em andamento e com resultado publicado; aguardando resultado inclui apostas cujo término já passou e ainda não possuem resultado publicado.

## Personalização e resgate de prêmios nos grupos

RF-35 Cor do nome — Todos iniciam com a cor de nome padrão atual. O integrante pode escolher qualquer cor RGB para seu nome naquele grupo. Cada mudança é uma nova compra, pelo preço vigente definido pelo dono.

RF-36 Título personalizado — O integrante pode definir um título de sua escolha, com no máximo 30 caracteres e sem emojis, exibido ao lado do nome no grupo. A primeira definição e cada alteração do texto exigem uma nova compra pelo preço vigente definido pelo dono. O título começa com a cor padrão; a compra do texto não inclui a escolha de uma cor personalizada.

RF-37 Cor do título — O integrante pode escolher qualquer cor RGB para o título. A escolha inicial de uma cor personalizada e cada mudança posterior exigem uma compra separada da definição ou alteração do texto. Alterar apenas o texto não implica comprar novamente a cor, e alterar apenas a cor não implica comprar novamente o texto.

RF-38 Preços e pagamento — Somente o dono define três preços independentes por grupo: mudança da cor do nome, definição ou alteração do texto do título e mudança da cor do título. Os preços podem ser zero. Cada compra desconta o preço vigente do saldo disponível do usuário naquele grupo; pontos reservados em apostas não podem ser utilizados. Saldo insuficiente impede a compra e a alteração. O débito e a personalização devem ocorrer na mesma operação, sem cobrança se a alteração falhar. Alterações de preço valem para novas compras, sem cobrança retroativa pelas personalizações existentes.

RF-39 Escopo, exibição e permanência — A cor do nome, o título e sua cor são independentes em cada grupo e aparecem em todos os lugares daquele grupo em que o nome do usuário seja exibido, incluindo chat, lista de integrantes e ranking. Não alteram a aparência do usuário em outros grupos ou em chats privados. Permanecem até serem alterados ou, no caso do título, removidos, sem prazo de expiração. Sair ou ser removido do grupo preserva a personalização para um eventual retorno.

RF-40 Remoção de título e moderação — O usuário pode remover o próprio título gratuitamente. Moderadores podem remover títulos apenas de membros comuns; o dono, que possui os poderes de moderação e permissões adicionais, pode remover o título de qualquer integrante. A remoção apaga o título e restaura sua cor padrão, sem alterar a cor do nome. Nenhuma remoção gera reembolso, independentemente de quem a realize; não são devolvidos os pontos gastos no texto nem na cor. Para definir um novo título após a remoção, o usuário paga novamente o preço vigente, e a escolha de uma cor personalizada é cobrada separadamente.

## Apresentação das apostas, ranking e estados vazios

RF-41 Destaque e participação nas listagens de apostas — Os cartões de apostas nas listagens do grupo e no Feed devem destacar a pergunta como informação principal. Cada cartão deve identificar a situação da aposta por um rótulo textual e por cores suaves e consistentes, diferenciando palpites abertos, palpites encerrados, aguardando resultado, resultado publicado, concluída e cancelada. A cor não deve ser o único meio de identificar a situação.

Os cartões devem apresentar uma barra de distribuição da participação entre as opções, com identificação das opções, quantidade de participantes e percentuais acessíveis em texto. Cada proporção corresponde à quantidade de palpites da opção dividida pelo total de palpites da aposta, independentemente dos pontos apostados. As barras não representam odds ou probabilidade de vitória. Os agregados devem respeitar os RF-13 e RF-14, sem expor escolhas ou valores individuais de outros participantes. A participação deve ser atualizada enquanto os palpites estiverem abertos; dados de apostas encerradas devem ser apresentados sem indicação de atualização em tempo real. Sem palpites, exibir essa informação e uma barra neutra, sem inventar percentuais. Carregamento e falha de consulta devem ser identificados, sem aparecer como ausência de participação.

RF-42 Destaque e posição pessoal no ranking — O ranking de cada grupo deve destacar visualmente os integrantes nas três primeiras posições, com marcadores distintos para primeiro, segundo e terceiro lugares, preservando a ordenação e o desempate do RF-20. Grupos com menos de três integrantes destacam apenas as posições existentes. Os destaques devem preservar as cores de nome e título adquiridas pelo usuário, conforme o RF-39.

O ranking deve apresentar a posição atual do usuário e, quando ele não for o primeiro, uma mensagem com a diferença entre seu saldo disponível e o saldo disponível do integrante imediatamente acima. Por exemplo: “Você está em 4º lugar, a 15 pontos do próximo colocado”. A diferença informa os pontos para alcançar o saldo desse integrante; igualar o saldo não garante ultrapassá-lo, pois o desempate continua seguindo o RF-20. Em caso de saldos iguais, informar o empate em pontos, sem sugerir uma diferença positiva. Para o primeiro colocado, apresentar uma mensagem de liderança. Posição e mensagem devem acompanhar as atualizações do ranking; falhas de consulta não devem apresentar valores inventados.

RF-43 Estados vazios com orientação e ação — As áreas de grupos, apostas, Feed, chat e ranking devem apresentar estados vazios com pequenos desenhos ou ícones, título curto e texto específico para o contexto. Quando houver uma ação útil e autorizada, o estado vazio deve incluir um botão ou link que leve diretamente ao fluxo correspondente, como “Publique a primeira aposta”, “Convide alguém para o grupo”, “Criar grupo” ou “Limpar filtros”.

O sistema deve diferenciar ausência de conteúdo de ausência de resultados após busca ou filtros. Por exemplo, um grupo sem apostas deve convidar à primeira publicação; uma listagem sem correspondências deve oferecer ajuste ou limpeza dos filtros; um grupo com apenas um integrante pode sugerir convidar outra pessoa, se esse integrante tiver permissão para enviar convites. As ações devem respeitar as permissões existentes e não sugerir operações indisponíveis ao usuário. Carregamento e falha de consulta devem ter mensagens próprias, sem serem tratados como estados vazios. Os desenhos não devem substituir o texto de orientação.

## Requisitos técnicos

RT-01 Execução na rede local — Uma única máquina executa os serviços de backend. Os demais computadores acessam a aplicação e se conectam ao backend da máquina anfitriã pela mesma rede Wi-Fi.

RT-02 Serviços e endpoints — O backend inclui um serviço em Java com Spring Boot e um serviço em Python com Flask. A equipe define a divisão de responsabilidades. Cada serviço deve oferecer pelo menos dois endpoints.

RT-03 Banco de dados — O sistema usa o Supabase para persistir os dados da aplicação.

## Critérios de aceitação

• Prazos. Uma aposta publicada às 10h com término às 20h pode ser cancelada pelo autor às 10h45. Às 11h01 o cancelamento pelo autor é rejeitado. Às 19h o servidor passa a rejeitar novos palpites.

• Pontos e odd. Ana aposta 10 pontos e Bruno aposta 30 na opção vencedora. Três pessoas apostam 80 pontos no total nas opções perdedoras. A odd da opção vencedora é 3,00 e os créditos finais são 30 pontos para Ana e 90 para Bruno.

• Contestação. Se a única contestação for removida às 21h45 e o prazo terminar às 22h, a aposta é apurada às 22h. Se a remoção ocorrer às 22h15, a aposta é apurada nesse momento. O palpite de quem contestou continua válido.

• Ranking. Com 100 pontos disponíveis, Ana fica à frente de Bruno, que tem 80\. Ao reservar 30 pontos numa aposta, Ana passa a ter 70 disponíveis e fica atrás de Bruno. Se a aposta for cancelada, ela recupera os 30 pontos e volta à frente, considerando apenas essas movimentações.

• Rede local. Dois usuários em computadores diferentes, conectados à mesma rede Wi-Fi, conseguem criar uma aposta e registrar um palpite, respectivamente. Ambos veem a contagem atualizada com os dois serviços executando somente na máquina anfitriã.

• Compras independentes. Com preços de 10 pontos para a cor do nome, 20 para o texto do título e 5 para a cor do título, personalizar os três itens desconta 35 pontos do saldo do grupo. Alterar depois apenas o texto custa mais 20 pontos, mantendo as cores existentes. Se um preço for zero, a ação correspondente é gratuita.

• Validação e saldo. Um título de 30 caracteres sem emojis é aceito; um de 31 caracteres ou com emojis é rejeitado sem débito. Cores RGB válidas são aceitas para nome e título. Se a compra custar 20 pontos e houver apenas 15 disponíveis, a operação é rejeitada sem alterar saldo ou aparência, mesmo que haja outros pontos reservados em apostas.

• Escopo e permanência. A personalização comprada no grupo A aparece em todos os lugares de A em que o nome seja exibido, sem afetar o grupo B ou chats privados. Sair e retornar a A restaura a personalização preservada, sem nova cobrança.

• Moderação sem reembolso. Um membro compra texto e cor de título. A remoção pelo próprio usuário, por um moderador autorizado ou pelo dono não credita pontos e restaura a cor padrão do título, mantendo a cor do nome. Moderadores não podem remover títulos de outros moderadores nem do dono. Comprar novamente texto e cor exige novos pagamentos pelos preços vigentes.

• Cartões e participação. Em uma aposta com três palpites na opção A e um na opção B, a listagem exibe uma distribuição de 75% e 25%, mesmo que os pontos apostados produzam outras proporções. A pergunta tem destaque, e a situação permanece identificável pelo texto sem depender da cor. Sem palpites, o cartão informa essa condição; uma falha na consulta informa indisponibilidade, sem exibir participação zero como se fosse um dado confirmado.

• Ranking pessoal. Com 100 pontos disponíveis para o terceiro colocado e 85 para o usuário em quarto, a interface destaca os três primeiros e informa a quarta posição e a diferença de 15 pontos. Se ambos tiverem 100, informa o empate em pontos e mantém o desempate pela criação da conta. O primeiro colocado recebe mensagem de liderança; um grupo com dois integrantes não exibe um terceiro colocado fictício. As cores de nome e título personalizadas permanecem visíveis.

• Estados vazios acionáveis. Um grupo sem apostas apresenta desenho, orientação e a ação “Publique a primeira aposta”, que abre o formulário de publicação. Uma busca ou filtro sem resultados oferece ajuste ou limpeza dos critérios. A ação “Convide alguém para o grupo” aparece somente para quem pode convidar e abre o fluxo de convite. Uma falha de carregamento apresenta erro e possibilidade de tentar novamente, em vez de afirmar que não há conteúdo. Esses estados e ações permanecem legíveis e utilizáveis em desktop e celular.

## Limites do produto

Os preços de personalização não são atribuídos automaticamente: o dono precisa
configurar os três preços, inclusive quando forem zero. Os prêmios disponíveis
são somente as personalizações descritas nos RF-35 a RF-40.
