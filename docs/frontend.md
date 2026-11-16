# Frontend temporário

Interface local em HTML/CSS/JavaScript, com servidor/proxy Python. A escolha da
arquitetura definitiva do frontend continua em aberto. Não há hospedagem externa
nem build obrigatório para usar este protótipo.

## Iniciar

As migrações V001–V011 devem estar aplicadas, incluindo odds por pontos (V010)
e personalização/ranking (V011). Siga a [primeira instalação](../README.md#primeira-instalação).
Use o `.env` já configurado e contas confirmadas no Supabase Auth.

Para iniciar os três serviços em um único terminal, na raiz:

```powershell
.\iniciar.cmd
```

Aguarde a mensagem de serviços prontos e abra **http://127.0.0.1:5173**. Ctrl+C
encerra os processos iniciados pelo comando. Confira [execucao-local.md](execucao-local.md)
para opções e tratamento de porta ocupada.

Para depurar os serviços separadamente, use os comandos abaixo.

Mantenha os dois backends em execução, em terminais separados na raiz do projeto:

```powershell
.\.venv\Scripts\python.exe scripts/writer.py bootRun
```

```powershell
.\.venv\Scripts\python.exe scripts/reader.py run
```

Reinicie o Spring e o servidor do frontend para carregar as rotas de chats e
amizades. Não inicie duas instâncias na mesma porta. O Flask de ranking e odds
não precisa ser alterado.

Em outro terminal, também na raiz:

```powershell
.\.venv\Scripts\python.exe scripts/frontend.py
```

Abra **http://127.0.0.1:5173**. Para outra porta, use `--port 5174`. O frontend
reutiliza as dependências Python já instaladas para os serviços. Não precisa de
npm nem compilação JavaScript para executar.

## Roteiro pelo navegador

A interface usa uma barra azul no topo e três colunas, inspiradas no Facebook
de 2012: grupos e amigos à esquerda, conversa selecionada ao centro e apostas do
grupo à direita. A busca filtra os grupos pelo nome. No celular, as colunas se
empilham para preservar a leitura e os controles.

O centro permite enviar mensagens no grupo selecionado ou para um amigo após
o aceite da amizade. A área privada não exibe apostas. A coluna do grupo começa
com o filtro **Ativas**, permite publicar e confirmar palpites e oferece o ranking
em uma seção expansível. Consulte o [roteiro dos chats](chats.md) para testar
pedidos de amizade, privacidade do histórico e moderação.
O [roteiro de resultados](resultados.md) cobre publicação do vencedor,
contestação, decisão do dono, cancelamento e pagamento automático.

1. Entre com a conta do autor.
2. Clique em **Criar grupo** e informe nome e pontos iniciais. A interface cria
   grupos públicos ou privados, com entrada imediata, solicitação ou convite.
   Pontos diários e prazos ficam no formulário expansível.
3. Clique em **Nova aposta** e informe pergunta, tipo, término e mínimo. Múltipla
   escolha aceita uma opção por linha. O critério de verificação é opcional.
4. Clique em **Outra sessão**. A nova aba começa sem a sessão da anterior.
5. Entre com outra conta, encontre o grupo em **Explorar grupos** e clique em
   **Entrar no grupo**. Não é necessário copiar IDs.
6. Abra a aposta, escolha uma opção e confirme o valor. Com saldo inicial 100 e
   palpite de 30, a tela mostra **70 disponíveis e 30 reservados**.
7. Acompanhe ranking e odds nas duas abas. O ranking é consultado periodicamente;
   odds usam long polling (até 15 segundos por consulta, verificações a cada 500 ms
   no reader). Novas consultas pausam em abas ocultas. O botão ↻ atualiza imediatamente.

O autor não pode confirmar palpites na própria aposta. Após confirmar, o
participante vê sua escolha/valor e os controles ficam bloqueados. Os demais
veem somente votos/odds agregados. A interface exibe dados reais das APIs, sem
inserir grupos, saldos ou palpites fictícios automaticamente.

## Login e sessões

Login e cadastro usam Supabase Auth. Com confirmação de email habilitada, o
cadastro orienta conferir a mensagem antes de entrar. SMTP e URLs de confirmação
dependem da configuração do projeto, conforme [supabase-auth.md](supabase-auth.md).
Para testar imediatamente, use as contas já confirmadas.

Tokens ficam em `sessionStorage`, separados por aba, permitindo recarregar sem
repetir o login. O cliente renova a sessão quando necessário. Senhas não são
armazenadas. **Sair** limpa a sessão local e solicita encerrar somente aquela
sessão no Auth, preservando a outra conta em teste.

Não carregamos JavaScript externo por CDN. Campos de usuários são inseridos como
texto. Arquivos são servidos com política de conteúdo restrita. O tratamento
definitivo de sessões pode ser revisto com a arquitetura de produção.

## Serviços e configuração pública

O navegador usa `/writer/...` e `/reader/...` no mesmo endereço do frontend. O
proxy encaminha somente rotas permitidas aos backends locais, preservando o token
de usuário. A autorização continua no Spring/Flask. Não é necessário abrir CORS
nos dois serviços para esta interface.

`/config` entrega somente a Project URL e a chave publishable pública. Senhas de
banco, credenciais administrativas e chaves `service_role` não são enviadas ao
navegador. Os destinos do proxy são portas locais do `.env`, sem proxy para URLs
arbitrárias. A interface não consulta as tabelas diretamente pelo Supabase.

Para acessar de outro computador na mesma rede, configure
`GAMBLR_BIND_ADDRESS=0.0.0.0`, reinicie os serviços necessários e abra
`http://IP_DA_MAQUINA_ANFITRIA:5173`. O acesso depende também do firewall local.
O proxy continua acessando os backends da máquina anfitriã.

## Novas rotas do Spring

- `GET /api/groups`: grupos em que o usuário autenticado é integrante atual.
- `GET /api/groups/public`: grupos públicos com entrada imediata.
- `GET /api/groups/{id}/bets`: propostas do grupo e somente o palpite do próprio
  usuário (`myPrediction`), quando houver.

Os detalhes da proposta incluem estado e critério de verificação. A listagem de
apostas exige participação atual. Grupos privados não aparecem na exploração.

## Apostas, ranking e estados vazios (RF-41 a RF-43)

Os cartões do grupo e do Feed destacam a pergunta e diferenciam seis situações
com rótulos e cores suaves. As barras usam quantidades de palpites por opção,
com contagem e percentuais em texto; não representam pontos ou probabilidades.
Uma aposta sem palpites usa uma barra neutra. Carregamento e indisponibilidade
têm mensagens próprias, sem representar falha como zero participantes.

O ranking permanece ordenado pelo servidor, conforme RF-20. Os três primeiros
recebem marcadores e fundos distintos, preservando as cores de nome e título.
A mensagem pessoal mostra posição, liderança, empate ou diferença de saldo
disponível para o integrante imediatamente acima. O cálculo da diferença usa
aritmética decimal exata, inclusive para valores fracionários. Igualar o saldo
não modifica o desempate por criação da conta.

Grupos, apostas, Feed, chats e ranking usam pequenos desenhos SVG e orientação
contextual nos estados vazios. As ações abrem os fluxos existentes de criação,
publicação, convite e escrita, ou limpam filtros. O convite só é oferecido
quando a configuração atual permite convidar. Falhas apresentam uma ação de
nova tentativa e não são anunciadas como ausência de conteúdo.

O Flask oferece `GET /api/groups/{id}/participation`, autorizado por participação
atual no grupo. Uma consulta à view `bet_odds` reúne os agregados de todas as
apostas daquele grupo, sem retornar escolhas, valores ou identidades individuais.
O proxy local encaminha essa rota apenas ao reader, preservando o Bearer token.
Grupo e Feed atualizam dados a cada cinco segundos enquanto a aba está visível.
Os cartões encerrados identificam a participação como encerrada. A consulta de
odds do detalhe mantém seu mecanismo existente de atualização.

Essas melhorias não exigem migração adicional. Para as personalizações já
existentes de nome e título, continua necessária a V011. Reinicie o reader e
o frontend e recarregue o navegador para disponibilizar a nova rota e os assets.

Validação em 15/11/2026: 42 testes Python, 19 testes JavaScript e 7 testes de
navegador aprovados. A integração do reader com PostgreSQL real também passou
em schema temporário, incluindo acesso permitido ao integrante, rejeição de
terceiros e de quem saiu, contagens agregadas e ausência de dados individuais.

## Limites atuais

O design aprovado é a única aparência da aplicação. Sombras, fundos,
tipografia, mensagens, controles e textos estão integrados à interface e à
folha de estilos principal. Veja [design da interface](acabamento-visual.md).

O menu compacto fica à esquerda e os contatos à direita em telas maiores.
No celular, os amigos ficam no menu para preservar o espaço de leitura.
Veja [layout lateral](layout-lateral.md).

A administração de grupos, convites, saída/remoção e crédito diário estão em
[administracao.md](administracao.md). Resultado, contestação, cancelamento e
pagamento estão em [resultados.md](resultados.md). A interface continua sendo um
protótipo temporário. Pontos são exclusivamente virtuais. Cores e títulos por
pontos estão em [personalização](personalizacao.md), com ativação pela V011;
outros tipos de prêmio permanecem em aberto.

## Testes

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s scripts/tests -v
npm.cmd --prefix frontend test
.\.venv\Scripts\python.exe scripts/writer.py integration
```

Para o navegador, com o frontend em execução:

```powershell
cd frontend
npm.cmd install
.\node_modules\.bin\playwright.cmd test
```

O teste usa Edge headless e respostas simuladas para testar duas sessões,
amizades, chats, moderação, formulários, recarga, filtros e layout móvel. As imagens ficam em
`frontend/test-results`, ignorado pelo Git. Os testes Java usam PostgreSQL real
em schema temporário para verificar listagens, reservas e privacidade. Os testes
do proxy conferem restrição de rotas e ausência de credenciais privadas.
