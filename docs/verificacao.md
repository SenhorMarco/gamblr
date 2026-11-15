# Verificação local — 15/11/2026

Revisão na branch `davi`, inicialmente limpa e três commits à frente de
`origin/davi`. Não houve push, merge, alteração de permissões do banco ou
aplicação de migrações. Integrantes do README fornecidos pelo responsável:
Ferreira, Brasil e Kossooski.

## Resultados

| Verificação | Resultado |
| --- | --- |
| `npm.cmd --prefix frontend test` | 21 aprovados, Node 24.14.1 |
| Instalação dos dois arquivos de requisitos Python | Concluída na `.venv`; Flask, PyJWT/crypto, dotenv e pg8000 disponíveis |
| `.venv/Scripts/python.exe -m pip check` | Sem dependências quebradas |
| `python -m unittest discover -s scripts/tests -v`, edição Store 3.13.14 | 40 aprovados; 2 falhas em encerramento de processos do supervisor |
| Mesma suíte no Python portátil oficial 3.13.14 | 42 aprovados, sem modificar testes ou código |
| `.venv/Scripts/python.exe scripts/writer.py build` | BUILD SUCCESSFUL; JDK Temurin 21.0.12, Gradle 9.9.1, Spring Boot 4.1.2 |
| Testes Java | 11 aprovados (8 JWT, 3 validação de personalização); 30 testes de integração ignorados pela condição `GAMBLR_IT` |
| Banco/integração Java–Python | Não executados: não há `.env`, CA nem banco isolado configurado; daemon Docker indisponível |
| Fluxo real no navegador | Não executado: faltam configuração Supabase, contas descartáveis e banco isolado |
| Playwright com respostas simuladas | Não executado nesta revisão; não substitui fluxo real |
| Linux | Instruções conferidas contra scripts; execução não testada |
| Inventário de rotas | 47 rotas Java e 4 Flask conferidas contra declarações; nenhuma ausente no README |
| Links e diff | Links locais válidos; `git diff --check` sem erros |

A inspeção dos arquivos rastreados não encontrou `.env`, certificados, ambientes
virtuais, dependências instaladas ou saídas de build. A varredura de padrões de
chaves privadas, tokens e URIs com senha não encontrou potenciais credenciais;
isso não equivale a auditoria do histórico completo. O JAR do Gradle Wrapper é
parte necessária do wrapper, não saída gerada da aplicação.

Os testes Python verificam contratos JSON, autorização, UUID/recurso inexistente,
proxy e long polling com dependências externas simuladas. Não comprovam consultas
ao PostgreSQL real nem login no Supabase. O build Java compila também os testes de
integração, mas compilação não significa execução desses cenários. Portanto,
**Java, Python e frontend não foram verificados juntos em fluxo real**.

## Limitação do supervisor no Python Microsoft Store

O primeiro ensaio no sandbox encontrou `EPERM` no Node e acesso negado aos
diretórios temporários no Python. Os comandos foram repetidos fora do sandbox:
Node passou; Python revelou falhas reais nos testes
`test_failed_service_stops_other_tree` e `test_interrupt_stops_child_and_grandchild`.
Eles detectaram descendentes ainda vivos após o encerramento do supervisor.

Uma sondagem confirmou que o executável da `.venv` da Store inicia interpretador
com PID diferente do retornado por `Popen`. Associar somente o PID real também
não resolveu toda a árvore nessa instalação. Não foi mantida alteração especulativa
no supervisor e nenhuma assertion foi enfraquecida. O suporte ao encerramento da
árvore nessa edição continua pendente. Prefira a distribuição convencional de
Python.org e execute a suíte para validar sua instalação antes do comando único.

Para distinguir essa limitação de falha da aplicação, foi usado o
[pacote embeddable oficial 3.13.14](https://www.python.org/downloads/release/python-31314/),
sem instalação global ou mudança de PATH. Seu SHA-256 foi conferido com a página
oficial. Extraído em `.tools/python-native`, recebeu em `python313._pth` o caminho
`../../.venv/Lib/site-packages` e `import site`, reutilizando as mesmas dependências.
Comando da comparação:

```powershell
.\.tools\python-native\python.exe -m unittest discover -s scripts/tests -v
```

Todos os 42 testes passaram nesse interpretador. Esse arranjo é diagnóstico local,
não substitui a instalação normal com `venv` descrita no README. Nenhum runtime,
ambiente virtual, certificado ou saída de build foi adicionado ao Git.

## O que falta verificar em ambiente isolado

Preparar um projeto Supabase de teste, roles e CA; aplicar V001–V011 uma vez;
configurar Email e chaves assimétricas; criar duas contas descartáveis. Executar
os verificadores de leitura do README, os testes de integração e o roteiro no
navegador. Confirmar escrita/publicação/reserva no Java e ranking/participação/odds
no Flask, com JSON e erros representativos, antes de afirmar integração completa.
Não executar os testes de banco sobre projeto compartilhado.
