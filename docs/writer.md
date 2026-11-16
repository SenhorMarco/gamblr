# Serviço de escrita: Spring Boot e Gradle

Consulte o [catálogo de endpoints](endpoints.md) para a finalidade, os parâmetros,
as respostas e as permissões de cada rota Java e Python.

O serviço em `services/writer` usa Spring Boot 4.1.2, Gradle Wrapper 9.9.1 e código
compilado com `release=21`. Use JDK 21.
Os scripts Gradle usam sintaxe Groovy (`build.gradle`). Não é necessário instalar
Gradle globalmente. O primeiro build baixa a distribuição e as dependências.

## 1. Preparar o banco até V011

Em instalação nova, siga o [README](../README.md#primeira-instalação) para roles,
certificado e V001–V011. O trecho abaixo explica a migração de identidades;
V005 sozinha não prepara o schema completo exigido pelo código atual.

A [V005](../database/migrations/V005__external_identities.sql) adiciona
`gamblr.user_identities`, que relaciona emissor/subject do JWT ao usuário interno.
O Flask não recebe acesso direto a essa tabela.

## 2. Compilar

No Windows/PowerShell, a partir da raiz:

```powershell
cd services/writer
.\gradlew.bat build
```

No Linux/macOS, use `sh gradlew build`. O build compila os testes de integração,
mas só os executa quando a variável `GAMBLR_IT=true` está explicitamente definida.
As versões das dependências são gerenciadas pelo BOM do Spring Boot.

## 3. Supabase Auth

O provedor escolhido é **Supabase Auth**. Configure `SUPABASE_URL` no `.env` e siga
[supabase-auth.md](supabase-auth.md) para o login por email/senha. O Spring valida
JWTs ES256/RS256 usando a JWKS do projeto, incluindo validade, issuer, audience
`authenticated`, role `authenticated` e subject preenchido.

O issuer é derivado de `SUPABASE_URL/auth/v1`. Use um **access token** de usuário no cabeçalho
`Authorization: Bearer TOKEN`. Credenciais de banco e API keys do Supabase não são
tokens de usuário. O cliente não pode escolher o ID interno nem enviar um cabeçalho
livre como `X-User-Id` para impersonar outro participante.

Sem URL do Supabase e sem override de issuer, somente `GET /health` fica acessível. As operações de negócio
ficam bloqueadas. Esse endpoint confirma que o processo HTTP está ativo; não é
um teste de prontidão do banco. Cadastro/login podem ser verificados por terminal;
o [frontend](frontend.md) já oferece login e renovação de sessão por aba.

## 4. Executar usando o .env

Da raiz do repositório, com as dependências Python de `scripts/requirements.txt`
instaladas na `.venv`:

```powershell
.\.venv\Scripts\python.exe scripts/writer.py bootRun
```

O lançador resolve o caminho do certificado, carrega apenas as variáveis necessárias
e usa a conta `gamblr_writer`. Senhas ficam no ambiente do processo e não nos
argumentos do comando. Não é necessário conceder DDL ao writer. O serviço exige
TLS com certificado e hostname verificados. O `.env` não é carregado automaticamente
por `gradlew bootRun`; ao usar Gradle diretamente, configure essas variáveis no
terminal/IDE. O diretório de trabalho do serviço é `services/writer`; use caminho
absoluto para o certificado na configuração da IDE.

Por padrão: `http://127.0.0.1:8080`. Para a demonstração na rede local, configure
`GAMBLR_BIND_ADDRESS=0.0.0.0` no `.env` e use o IP da máquina anfitriã nos clientes.
O [Flask](reader.md) usa outra porta na mesma máquina. O [frontend](frontend.md)
usa um proxy local para acessar os dois serviços pelo navegador.

## Endpoints

A lista abaixo cobre o fluxo básico. O [inventário completo no README](../README.md#endpoints)
inclui os cinco controllers e suas exigências de autenticação/autorização.

| Método e caminho | Operação |
| --- | --- |
| `GET /health` | Estado do processo HTTP, público |
| `POST /api/me` | Provisionar perfil interno para o JWT validado; repetição retorna o perfil existente |
| `POST /api/groups` | Criar grupo, dono/integrante, carteira e crédito inicial na mesma transação |
| `GET /api/groups` | Listar meus grupos |
| `GET /api/groups/public` | Descobrir grupos públicos |
| `GET /api/groups/{id}/bets` | Listar apostas do grupo |
| `POST /api/groups/{id}/members/me` | Entrar/retornar a grupo público de entrada imediata |
| `GET /api/groups/{id}/wallet/me` | Consultar a própria carteira |
| `POST /api/groups/{id}/bets` | Publicar aposta e suas opções na mesma transação |
| `GET /api/bets/{id}` | Consultar a proposta, somente como integrante atual |
| `POST /api/bets/{id}/predictions` | Confirmar palpite e reservar pontos na mesma transação |
| `GET /api/bets/{id}/prediction/me` | Consultar somente o próprio palpite |

### Exemplo de fluxo

Para executar automaticamente este roteiro com duas contas reais, siga
[demonstracao.md](demonstracao.md). O script solicita os logins interativamente
e confere o saldo antes/depois da reserva e da tentativa de palpite duplicado.

Use dois usuários com tokens distintos. Primeiro, cada um chama `POST /api/me`:

```json
{"displayName":"Ana"}
```

O autor chama `POST /api/groups`:

```json
{
  "name":"Grupo de demonstração",
  "initialPoints":100,
  "dailyPoints":5,
  "settlementSeconds":3600,
  "contestSeconds":3600,
  "visibility":"PUBLIC",
  "entryMode":"IMMEDIATE",
  "invitePolicy":"ALL_MEMBERS"
}
```

Os valores são exemplos da demonstração, não novos padrões de produto. O dono
recebe o ID do grupo. O segundo usuário entra em `/api/groups/ID/members/me`.
O autor publica em `/api/groups/ID/bets`, com término futuro no momento da execução:

```json
{
  "question":"Vai chover durante o evento?",
  "type":"BINARY",
  "endsAt":"2030-01-01T20:00:00-03:00",
  "minimumPoints":10,
  "verificationCriterion":"Observação durante o evento"
}
```

A resposta contém o ID da aposta, os IDs das opções Sim/Não e os prazos calculados.
Para `MULTIPLE_CHOICE`, envie também `options`, com pelo menos duas opções distintas.
O segundo usuário confirma em `/api/bets/ID/predictions`:

```json
{"optionId":"UUID_DA_OPCAO","points":30}
```

A carteira passa de 100 disponíveis/0 reservados para 70 disponíveis/30 reservados.
Repetir a confirmação retorna `409 PREDICTION_EXISTS`; não reserva novamente.
Após timeout ou erro de comunicação, consulte `prediction/me` e a carteira antes
de tentar novamente. O autor não pode palpitar na própria proposta.

## Transações e prazos

O protocolo de confirmação adquire o lock do grupo e bloqueia aposta, integrante
e carteira nessa ordem,
valida o horário do servidor após adquirir os bloqueios e registra palpite,
movimentação e saldos na mesma transação. O bloqueio da carteira impede gasto
duplicado mesmo em apostas diferentes. Aos 90% da duração, o servidor rejeita
novos palpites, independentemente de um job de fechamento.

A publicação copia os prazos do grupo. Não há endpoint de edição de aposta,
opções ou palpite. Saída/remoção, transferência e apuração implementadas respeitam
o protocolo de bloqueios e as regras documentadas em `requisitos.md`.

O retorno ao grupo reutiliza a carteira e a primeira entrada; não concede outro
crédito inicial. O endpoint de entrada implementa somente grupos públicos com
entrada imediata; convites e aprovação de pedidos têm endpoints próprios
implementados em [administração](administracao.md).

## Testes de integração no PostgreSQL real

Execute somente em banco de teste confirmado como isolado, nunca em banco
compartilhado. Da raiz, configure credenciais administrativas, de migração,
de escrita e de leitura e o certificado CA no `.env`:

```powershell
.\.venv\Scripts\python.exe scripts/writer.py integration
```

Os testes criam um schema exclusivo `gamblr_it_` seguido de UUID, aplicam as
migrações nele, executam chamadas HTTP com JWTs assinados por um emissor local de
teste e chamadas concorrentes. O schema temporário é removido no encerramento;
nenhuma tabela de `gamblr` é usada para inserir dados de teste. A credencial
administrativa é usada apenas na preparação/remoção desse schema.

Se o processo for encerrado à força, o schema temporário pode permanecer e deverá
ser inspecionado e removido separadamente. O teste valida o nome gerado antes de
remover e nunca executa `DROP SCHEMA gamblr`.

Os testes cobrem crédito inicial, reentrada, snapshot de prazos, privacidade,
prazo exato dos 90%, opção inválida, saldo insuficiente, concorrência de reservas
e rollback integral se o registro de movimentação falhar. O emissor de teste não
é um provedor de autenticação para uso real.
Também há um teste que executa o núcleo do roteiro Python pelo HTTP contra o
Spring e o PostgreSQL temporário.

## Funcionalidades complementares

Chats de grupo e privados estão implementados em [chats.md](chats.md). O ciclo
de resultado, contestação, decisão do dono, cancelamento e pagamento está em
[resultados.md](resultados.md), introduzido pela V008. Convites, aprovação de
entrada, crédito diário e administração de integrantes estão em
[administracao.md](administracao.md), introduzidos pela V009. Cores e títulos por pontos estão em
[personalização](personalizacao.md), introduzidos pela V011.
