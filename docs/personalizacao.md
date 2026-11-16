# Personalização por pontos nos grupos

Implementação dos RF-35 a RF-40 e da extensão do RF-04. Cada integrante pode
comprar a cor do nome, o texto de um título e a cor desse título, com preços
independentes definidos pelo dono. Remover um título nunca gera reembolso.

## Ativar

1. Com V001–V010 já aplicadas, execute todo o arquivo
   [V011__group_personalization.sql](../database/migrations/V011__group_personalization.sql)
   no SQL Editor do Supabase como `postgres`. A migração é manual; os testes
   aplicam migrações somente em schemas temporários.
2. Reinicie os serviços e recarregue o navegador, conforme
   [execução local](execucao-local.md).
3. O dono abre **Gerenciar grupo → Personalização no grupo**, define os três
   preços e usa **Salvar preços**. Zero permite compras gratuitas.

O sistema não atribui preços automaticamente: em grupos novos e existentes, compras ficam
desabilitadas até o dono configurar os três preços. O servidor também rejeita
compras sem preço configurado.

## Usar pelo site

- Todo integrante encontra suas opções em **Gerenciar grupo**. Cada botão
  informa o preço da ação. Comprar o texto do título não compra sua cor.
- O título tem de 1 a 30 caracteres, sem emojis ou quebras de linha. O servidor
  normaliza o texto para Unicode NFC e conta os pontos de código. Remover usa
  uma ação própria, em vez de comprar um texto vazio.
- Cores usam o seletor do navegador e são enviadas como RGB hexadecimal de
  seis dígitos (`#RRGGBB`), sem transparência. Qualquer RGB é aceito.
- Cada alteração válida desconta o preço vigente do saldo disponível daquele
  grupo. O saldo reservado em apostas permanece intacto. Reaplicar exatamente
  o valor já usado é rejeitado sem cobrança.
- Alterar só o texto preserva a cor adquirida para o título. Remover o título
  restaura sua cor padrão e preserva a cor do nome. Uma compra posterior do
  título começa na cor padrão, e personalizar a cor exige nova compra.
- O dono pode remover títulos de qualquer integrante; moderadores podem
  remover os de membros comuns. Todos podem remover o próprio título.
  Nenhum desses casos devolve pontos.
- Nome e título são renderizados como texto no chat, na lista de integrantes e
  no ranking. As mensagens antigas usam a aparência atual do autor. Sair e
  retornar preserva as personalizações; outros grupos e chats privados não
  recebem essa aparência.

## Transações, repetição e autorização

As compras, as configurações de preço e as remoções compartilham o lock
transacional do grupo com a administração e as apostas. A compra bloqueia a
carteira e grava o débito, o movimento `PERSONALIZATION`, o comprovante de compra
e a aparência na mesma transação. Falhas desfazem a operação inteira.

Cada compra recebe um `clientId` UUID e o `expectedPrice` mostrado ao usuário.
Repetir a mesma compra com a mesma chave não cobra novamente, mesmo se a
resposta original se perder. A interface conserva essa chave ao repetir uma
tentativa que falhou, enquanto o formulário continuar aberto. Usar a chave
com outro conteúdo gera conflito. Se o preço mudar antes da compra, o servidor
rejeita a tentativa; atualize a administração para consultar o novo preço.
Repetir uma compra já confirmada não restaura um título removido depois.

O servidor verifica a identidade do JWT, a participação atual, o dono e a
hierarquia de moderação. A tabela imutável `personalization_purchases` possui RLS
restrita ao writer; o reader recebe as cores e o título somente pela view
`group_ranking`, sem acesso aos comprovantes ou às tabelas privadas.

## APIs autenticadas do writer

| Método | Rota | Corpo ou resposta |
| --- | --- | --- |
| GET | `/api/groups/{id}/personalization` | `prices` e `style` do próprio integrante |
| POST | `/api/groups/{id}/personalization/prices` | `nameColor`, `title`, `titleColor`: preços não negativos; somente dono |
| POST | `/api/groups/{id}/personalization/purchases` | `action`, `value`, `clientId`, `expectedPrice` |
| POST | `/api/groups/{id}/members/{userId}/title/remove` | Sem corpo; remoção própria ou autorizada |

`action` aceita `NAME_COLOR`, `TITLE` e `TITLE_COLOR`. Os preços do GET são
strings decimais, ou `null` antes da configuração. `style`, as mensagens de
grupo, os integrantes e o ranking incluem `nameColor`, `title` e `titleColor`;
cores `null` usam a aparência padrão. Mensagens privadas não recebem os valores
do grupo.

## Verificação

Comandos Windows/PowerShell, na raiz. A integração exige banco isolado;
veja [testes e limites](../README.md#testes-e-limites).

Os testes de integração Java aplicam V001–V011 em um schema temporário e
verificam saldo, repetição, isolamento entre grupos, retorno, moderação,
ausência de reembolso, validação e compras concorrentes. Os testes unitários
Java cobrem título e RGB; os testes Python cobrem o ranking e as rotas do proxy.
O teste de navegador `personalization.spec.js` simula uma resposta perdida e
verifica cobrança única, exibição, remoção e o layout em desktop e celular.

```powershell
.\.venv\Scripts\python.exe scripts/writer.py test
.\.venv\Scripts\python.exe scripts/writer.py integration
.\.venv\Scripts\python.exe -m unittest discover -s scripts/tests -v
```

Para executar somente os cenários de integração de personalização:

```powershell
.\.venv\Scripts\python.exe scripts/writer.py integration --tests '*WriterIntegrationTest.personalization*' --tests '*WriterIntegrationTest.concurrentPersonalization*'
```

Com o frontend em execução, na pasta `frontend`:

```powershell
npm.cmd test
.\node_modules\.bin\playwright.cmd test
```
