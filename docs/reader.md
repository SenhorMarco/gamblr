# Serviço de consultas: Flask

Consulte o [catálogo de endpoints](endpoints.md) para a finalidade, os parâmetros,
as respostas e as permissões de cada rota Java e Python.

O serviço `services/reader` oferece ranking, participação e odds. Ele valida os tokens do
Supabase Auth e usa somente a credencial `gamblr_reader`. O Spring continua
responsável pelas escritas; o Flask consulta as views depois dos commits.

Para as barras das listagens, `GET /api/groups/{id}/participation` retorna os
agregados de todas as apostas do grupo em uma consulta. A resposta contém
`groupId` e `bets`, com `betId`, `totalVotes` e `options` (`optionId`, `label`,
`position`, `votes`). O serviço exige participação atual antes da consulta e
usa o mesmo snapshot de leitura para autorização e agregados. Não retorna
identidades, escolhas ou valores individuais. Os detalhes de interface estão
em [frontend](frontend.md#apostas-ranking-e-estados-vazios-rf-41-a-rf-43).

## 1. Preparar o banco até V011

Siga a [primeira instalação](../README.md#primeira-instalação). Todas as migrações
V001–V011 são necessárias. A V006 acrescenta emissor/subject à view
`current_group_members` para autorização. A V011 acrescenta `nameColor`, `title`
e `titleColor` ao ranking; a verificação de inicialização exige essas colunas.
Não há acesso direto a `user_identities`, `predictions` ou outras tabelas privadas.

## 2. Dependências e configuração

Windows/PowerShell, na raiz do projeto:

```powershell
.\.venv\Scripts\python.exe -m pip install -r scripts/requirements.txt -r services/reader/requirements.txt
```

Crie antes a `.venv` e instale os dois arquivos de requisitos conforme o
[README](../README.md#primeira-instalação). O serviço reutiliza as
variáveis existentes no `.env`: `SUPABASE_URL`, host/porta/nome do banco,
certificado CA, `GAMBLR_READ_DB_USER` e `GAMBLR_READ_DB_PASSWORD`. Sua porta
padrão é 5000; para mudar, acrescente `GAMBLR_READER_PORT=5000` com outro valor.
Não copie o `.env.example` sobre o arquivo configurado.

O lançador lê o `.env`, resolve o caminho do certificado e usa apenas as variáveis
de conexão necessárias. O serviço não precisa de credencial administrativa, de
migração, de escrita nem de chave `service_role`. A chave publishable só é usada
pelo cliente que faz login no Auth, não pela API Flask.

## 3. Conferir e iniciar

Primeiro, confira conexão, identidade da conta e colunas das views:

```powershell
.\.venv\Scripts\python.exe scripts/reader.py check
```

Depois, em um terminal separado do Spring:

```powershell
.\.venv\Scripts\python.exe scripts/reader.py run
```

O endereço padrão é `http://127.0.0.1:5000`. O lançador recusa iniciar caso a
conexão/conta/views não possam ser verificadas. Usa TLS com certificado e hostname
validados. Debug e reloader ficam desativados.

`GAMBLR_BIND_ADDRESS=0.0.0.0` permite escutar na rede local, assim como no Spring.
O lançador usa o servidor Flask para execução local. O [frontend](frontend.md) acessa os serviços por um proxy
de mesma origem, sem exigir configuração de CORS entre portas no navegador.

## Endpoints

| Método e caminho | Retorno |
| --- | --- |
| `GET /health` | Estado do processo HTTP, sem autenticação e sem consulta ao banco |
| `GET /api/groups/{id}/ranking` | Ranking de integrantes atuais por saldo disponível |
| `GET /api/groups/{id}/participation` | Votos agregados de todas as apostas do grupo |
| `GET /api/bets/{id}/odds` | Votos e odds agregados por opção |

Envie `Authorization: Bearer ACCESS_TOKEN` nos três endpoints de negócio. A API
valida assinatura ES256/RS256, emissor do projeto, audiência/papel `authenticated`,
validade e subject. Depois, verifica o vínculo de identidade e a participação
atual no grupo. Um token válido de usuário externo ao grupo recebe `403`.
Um usuário sem perfil provisionado/participação também não tem acesso; o Flask
não cria perfil nem faz o usuário entrar no grupo.

Cada requisição de dados usa uma transação PostgreSQL **somente leitura**, em
snapshot repetível, encerrada com rollback. A autorização e os dados são lidos
no mesmo snapshot. Uma saída/remoção confirmada impede as requisições seguintes.
A API confere ainda se a conexão usa efetivamente a role `gamblr_reader`.

Exemplo de ranking:

```json
{
  "groupId": "UUID_DO_GRUPO",
  "ranking": [
    {"position": 1, "userId": "UUID_AUTOR", "displayName": "Autor", "availablePoints": "100", "nameColor": null, "title": null, "titleColor": null},
    {"position": 2, "userId": "UUID_PARTICIPANTE", "displayName": "Participante", "availablePoints": "70", "nameColor": null, "title": null, "titleColor": null}
  ]
}
```

Pontos são retornados como strings decimais para preservar a precisão. Reservas
ficam fora do saldo usado no ranking. O desempate segue a criação da conta no
Gamblr, conforme a view da V004.

Exemplo de opção nas odds:

```json
{"optionId":"UUID_OPCAO","label":"Sim","position":0,"votes":2,"decimalOdds":"2.50"}
```

A resposta completa inclui `betId`, `groupId`, `totalVotes` e `options`. Opção
sem pontos tem `decimalOdds: null`, a ser exibida como “—”. A formatação da odd usa
duas casas decimais; desde V010 a odd é **total de pontos da aposta dividido
pelos pontos da opção**. A contagem de votos é independente da odd. Não retornamos
escolhas individuais ou valores dos palpites. Os votos
de ex-integrantes permanecem nos agregados, conforme RF-19.

As odds podem ser consultadas por integrantes mesmo sem terem apostado. O
endpoint também mantém os agregados de apostas encerradas disponíveis aos
integrantes; a interface indica o estado/prazo a partir da proposta
consultada no Spring. Esses números não são uma promessa de pagamento: a
distribuição segue o RF-07 e está implementada no Java; veja [resultados](resultados.md).

## Consultar com uma conta real

Use os IDs exibidos na [demonstração](demonstracao.md). Em outro terminal, na raiz:

```powershell
.\.venv\Scripts\python.exe scripts/query_reader.py ID_DO_GRUPO ID_DA_APOSTA
```

Substitua os dois IDs pelos UUIDs reais. Informe o email/senha de um integrante
atual. O script faz login, consulta os dois endpoints e exibe o JSON sem mostrar
ou salvar tokens. Não exige copiar tokens do painel ou de outro terminal.

Para acompanhar mudanças:

```powershell
.\.venv\Scripts\python.exe scripts/query_reader.py ID_DO_GRUPO ID_DA_APOSTA --watch
```

O cliente consulta a cada 2 segundos e imprime somente mudanças. Cada resposta
consulta o PostgreSQL, sem cache de dados (`Cache-Control: no-store`). Este é o
mecanismo deste cliente de terminal; o frontend usa long polling para odds,
conforme descrito abaixo. Não há WebSocket/SSE.
Encerre com `Ctrl+C`. A sessão de consulta é encerrada ao final. Se o token
expirar, execute novamente o comando para fazer novo login; renovação automática
no cliente ainda não está implementada.

O script de consulta é local à máquina anfitriã e não faz escritas no domínio.
Na demonstração de uma aposta com um palpite em Sim, o esperado é: 1 voto em Sim,
odd `1.00`, 0 votos em Não e odd `null`; ranking com autor 100 e participante 70.

## Testes

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s scripts/tests -v
.\.venv\Scripts\python.exe scripts/writer.py integration
```

Execute integração somente em banco isolado, com as quatro credenciais e CA
configurados; não use banco compartilhado. O teste cria dados pelo Spring e
consulta o Flask com a credencial
real de leitura em schema temporário. Verifica 2 votos vencedores de um total de
5, odds por pontos `3.00` e `1.50`, opção sem pontos, ranking/desempate,
rejeição de usuário externo,
preservação dos votos após saída e ausência de permissões para tabelas privadas
ou escritas. As credenciais administrativas só são usadas pelo teste para
preparar/remover seu schema; o serviço Flask não as utiliza.

Os testes não aplicam migrações ao schema de domínio.

## Odds por pontos (V010)

A V010 define a odd como total de pontos dividido pelos pontos da opção. Sem pontos,
a odd é nula. Votos continuam sendo uma contagem separada. A barra contínua da interface
mostra a proporção de pessoas por opção (votos da opção / total de votos),
independentemente do valor apostado. As odds continuam calculadas por pontos.

## Atualização contínua (RF-13)

A interface mantém uma consulta autenticada de odds pendente por até 15 segundos,
usando `X-Odds-Version`. O reader verifica mudanças a cada 500 ms com snapshots
novos e revalida a participação; responde ao detectar mudança ou ao vencer a espera.
O cliente reconecta automaticamente e pausa novas consultas em abas ocultas.
O proxy encaminha apenas versões SHA-256 válidas. Não exige nova migração.
