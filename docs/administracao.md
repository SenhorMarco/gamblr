# Administração dos grupos e crédito diário

Implementação dos RF-02, RF-17, RF-18, RF-26 a RF-29, com as operações de saída e
retorno relacionadas aos RF-03 e RF-19. A publicação de apostas também gera um
evento no histórico do chat, conforme o RF-23.

## Ativar

1. Na branch `marco`, com V001–V008 já aplicadas, execute todo o conteúdo de
   [V009__group_administration.sql](../database/migrations/V009__group_administration.sql)
   no SQL Editor do Supabase como `postgres`. O arquivo já configura RLS e
   permissões. Não execute novamente as migrações anteriores.
2. Pare as instâncias antigas e inicie os serviços novamente:

   ```powershell
   .\iniciar.cmd
   ```

   No Linux, use `./iniciar.sh`.
3. Recarregue o navegador. A migração é manual e não altera o `.env`.

As migrações dos testes são aplicadas somente a schemas temporários. Elas não
atualizam automaticamente o schema `gamblr` do ambiente em uso.

## Roteiro pelo site

1. **Criar grupo:** escolha público ou privado, entrada imediata, por solicitação
   ou por convite e quem pode convidar. Grupos privados exigem convite.
2. **Gerenciar grupo:** abra pelo cabeçalho da conversa. Todos os integrantes
   podem consultar a lista e sair. O dono também pode alterar nome, pontos
   iniciais para futuros ingressantes, crédito diário, prazos e regras de entrada.
   A alteração não modifica o saldo dos atuais integrantes nem apostas publicadas.
3. **Convites:** em Gerenciar grupo, pesquise uma pessoa pelo nome e envie o
   convite. O destinatário aceita ou recusa pela coluna esquerda. A pessoa deve
   ter um perfil no Gamblr. Não é necessário já ser amigo dela.
4. **Solicitações:** grupos públicos com solicitação aparecem em Explorar. O
   interessado usa Solicitar entrada; dono e moderadores aprovam ou recusam em
   Gerenciar grupo. Membros comuns não podem aprovar. Mudar a forma de entrada
   para outra modalidade encerra as solicitações que ainda estavam pendentes.
5. **Moderadores:** somente o dono pode promover ou destituir. Moderadores podem
   remover membros comuns, mas não outros moderadores nem o dono. O dono pode
   remover membros comuns e moderadores. Para sair, use Sair do grupo.
6. **Transferência:** o dono usa Transferir propriedade na linha de outro membro.
   A operação é imediata, e o antigo dono torna-se moderador. Contestações
   pendentes passam a ser decididas pelo novo dono.
7. **Sucessão:** se o dono sair sem transferir, assume o moderador atual com a
   primeira entrada mais antiga; sem moderadores, assume o membro atual mais
   antigo. Retornos preservam a primeira data de entrada. Empates exatos de data
   usam o UUID como critério técnico determinístico.
8. **Último integrante:** se o dono sair sendo o último, o grupo deixa de aparecer
   nas APIs e na interface. Convites e solicitações pendentes são encerrados.
9. **Apagar aposta:** somente o dono encontra o botão no detalhe. Uma aposta
   pendente é cancelada com devolução das reservas. Uma concluída é ocultada e
   mantém os pagamentos. O evento correspondente no chat passa a ser um aviso
   de mensagem apagada. Moderadores não podem apagar apostas.
10. **Aposta no chat:** novas publicações aparecem na conversa com Ver aposta,
    que abre o detalhe na coluna direita. Publicações anteriores à V009 não
    geram eventos retroativos. Entrantes continuam sem acesso ao chat anterior
    à entrada; apostas abertas permanecem acessíveis pela coluna de apostas.

## Saída, retorno e histórico

Sair ou ser removido bloqueia imediatamente leitura e envio de mensagens. A
carteira e os palpites são preservados; uma nova entrada não concede outro
crédito inicial. O retorno ocorre como membro comum, sem restaurar privilégios
de moderação. O dono pode promovê-lo novamente.

Os efeitos sobre apostas acontecem na mesma transação da saída: cancelar apostas
do autor que ainda não publicaram resultado e retirar contestações pendentes
da pessoa que saiu. Se o prazo de contestação já terminou e não restar pendência,
o pagamento ou devolução é processado; caso contrário, o prazo continua.
Resultados já publicados continuam válidos mesmo se o autor sair. As carteiras
de ex-integrantes recebem os movimentos normalmente.

A exclusão do grupo e das apostas é lógica, preservando os registros necessários
ao histórico financeiro. Isso evita apagar reservas ou repetir pagamentos.
Apostas com resultado já publicado ainda podem ser apuradas pelo worker após o
encerramento do grupo, conforme as regras dos RF-10 e RF-19.

## Crédito diário

O horário de referência é **00:00 em America/Sao_Paulo**, usando o relógio do banco.
Um agendamento roda à meia-noite, e uma verificação periódica retoma datas que
não foram processadas enquanto o backend esteve desligado.

O valor e os beneficiários são os vigentes naquele instante, consultados nos
históricos `group_daily_rates` e `membership_periods`. Entrar depois da meia-noite
não concede o crédito daquele dia. Sair depois da meia-noite não elimina um
crédito devido. Alterar o valor mais tarde não modifica o crédito passado.

Cada execução trata até 31 datas por grupo; atrasos maiores continuam nos lotes
seguintes. Cada data processada é registrada em `group_daily_runs`, e o índice
único de movimentos protege cada usuário/grupo/data. Movimento, carteira e marca
de processamento são gravados na mesma transação. Se falhar, tudo é desfeito.

A recuperação começa na primeira meia-noite coberta pelo histórico da V009.
Valores e participações de datas anteriores que não foram registrados não são
inventados nem creditados retroativamente. O Java precisa estar em execução para
processar os créditos; uma parada atrasa a execução, sem perder as datas cobertas.

## Concorrência e autorização

As escritas do mesmo grupo adquirem um lock transacional compartilhado entre
administração, publicação, palpites e apuração. Assim, uma remoção não entra em
conflito com uma confirmação ou pagamento, e a sucessão vê um conjunto consistente
de membros. Carteiras são bloqueadas em ordem estável.

Todas as permissões são conferidas no Spring pelo usuário do JWT. Os controles
da interface não substituem essas verificações. A conta Flask continua lendo
somente views autorizadas; grupos e apostas excluídos deixam de aparecer nelas.

## APIs autenticadas adicionadas

| Método | Rota | Uso |
| --- | --- | --- |
| GET / POST | `/api/groups/{id}/settings` | Consultar/alterar configuração |
| GET | `/api/groups/{id}/members` | Listar integrantes atuais |
| POST | `/api/groups/{id}/members/{userId}/role` | Definir `role`: `MEMBER` ou `MODERATOR` |
| POST | `/api/groups/{id}/members/{userId}/remove` | Remover integrante |
| POST | `/api/groups/{id}/leave` | Sair e executar sucessão/exclusão |
| POST | `/api/groups/{id}/transfer` | Transferir com `userId` |
| POST | `/api/groups/{id}/invitations` | Convidar com `userId` |
| GET | `/api/group-invitations` | Listar convites pendentes próprios |
| POST | `/api/group-invitations/{id}/accept` ou `/decline` | Responder convite |
| GET / POST | `/api/groups/{id}/join-requests` | Listar fila ou solicitar entrada |
| POST | `/api/groups/{id}/join-requests/{requestId}/approve` ou `/decline` | Responder solicitação |
| POST | `/api/bets/{id}/delete` | Apagar aposta pelo dono |

O POST de configurações recebe os mesmos campos da criação do grupo. Convites
não têm prazo de expiração nesta etapa; um convite válido emitido continua
pendente até ser respondido ou até o grupo ser excluído. A primeira concessão
de saldo e a entrada são atômicas em qualquer modalidade.

Os testes Java usam PostgreSQL em schema temporário; os testes de navegador usam
três sessões com respostas simuladas. Prêmios internos continuam em aberto,
e a arquitetura definitiva do frontend permanece uma escolha técnica futura.
