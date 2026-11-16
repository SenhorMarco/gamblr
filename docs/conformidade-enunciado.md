# Conferência de organização — Projeto Final

Fonte: `Projeto Final - Enunciado.pdf`, fornecido na pasta Downloads,
Laboratório de Programação II, páginas 1 e 2. Revisão em 15/11/2026.
O escopo desta revisão é a organização dos arquivos e a documentação de execução,
conforme orientação do responsável. Integrantes, vídeo e divisão do trabalho
serão completados pela equipe.

## Requisitos relacionados ao repositório

| Orientação do enunciado | Situação dos arquivos | Evidência |
| --- | --- | --- |
| API Python com pelo menos dois endpoints funcionais | Atendido na estrutura e no código | `services/reader/gamblr_reader/__init__.py`: ranking, odds e participação, além de health |
| API Java/Spring com pelo menos dois endpoints funcionais | Atendido na estrutura e no código | `services/writer`: grupos, apostas, carteira, chat e administração |
| Frontend consumindo as duas APIs | Atendido na estrutura e no código | `frontend/static/app.js`: chamadas `writer` e `reader`; `services/web/server.py`: proxy para os dois backends |
| APIs independentes em portas diferentes | Atendido na configuração | Java 8080, Python 5000; site/proxy 5173; supervisor rejeita portas coincidentes |
| Três partes em pastas separadas | Atendido | `services/reader`, `services/writer`, `frontend`; os nomes do exemplo do PDF não são obrigatórios |
| README com nome e tecnologias | Atendido após revisão | Objetivo, árvore de diretórios, tecnologias e portas no README |
| README com dependências e execução de cada parte | Corrigido | Preparação do clone, `.venv`, dependências, banco, Auth, certificado, configuração e comandos para Windows/Linux |
| README com endpoints de cada API | Corrigido | Listagem das 47 rotas Java e 4 rotas Python, incluindo health |
| Arquivos desnecessários e credenciais fora do Git | Atendido na árvore versionada inspecionada | `.env.example` contém somente modelo; `.env`, certificados, `.venv`, builds e dependências não estão versionados |
| Erros básicos e respostas JSON | Evidências existentes no código | `ApiErrors.java`, handlers do reader e do proxy; respostas sem conteúdo de ações não carregam corpo JSON |
| Integrantes, vídeo e divisão do trabalho | Responsabilidade da equipe, fora desta revisão | Campo de integrantes sinalizado no README, sem atribuições presumidas por histórico de commits |
| Repositório público ou compartilhado com professor | A conferir pela equipe | A revisão local dos arquivos não comprova as permissões de acesso do professor |

## Mudanças realizadas

- README autossuficiente para preparação e execução, com referências aos arquivos
  de bootstrap/migração do próprio repositório. Os guias em `docs` são complementares.
- Tabela de responsabilidades das APIs e identificação das chamadas no navegador.
- Lista de rotas conferida contra os decorators Flask e as anotações dos controllers.
- Modelo `.env.example` alinhado ao requisito real de certificado CA e TLS.
- Referências de migração na execução Linux e no lançador do reader atualizadas
  para V011.

As pastas existentes foram mantidas: já separam as três partes exigidas. Renomeá-las
para os nomes ilustrativos do PDF não acrescentaria conformidade e exigiria alterar
caminhos de scripts, imports, testes e documentação.

## Limites da verificação

Foram inspecionados arquivos versionados, configuração de portas, pontos de entrada,
tratamento de erros e consumo das APIs pelo frontend. O Gradle Wrapper e o script
de início Linux têm permissão executável no Git. Os comandos e links do README
foram conferidos contra os arquivos e parâmetros disponíveis no repositório.

A listagem do README foi comparada automaticamente com o código: 47 rotas Java
e 4 rotas Python correspondem aos controllers e decorators atuais. Os links
locais existem e os 42 testes Python de suporte e APIs passaram após a revisão.

Esta revisão não provisiona um novo projeto Supabase nem substitui uma execução
de instalação em um clone novo e em outro sistema operacional. A preparação do
banco, das contas e do `.env` permanece explícita no README, pois não é fornecida
por arquivos públicos com credenciais da equipe.
