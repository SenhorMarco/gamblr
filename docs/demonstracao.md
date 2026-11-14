# Demonstração: grupo, aposta e reserva de pontos

Este roteiro usa duas contas do Supabase Auth e as APIs do Spring Boot. O fluxo
termina com o palpite confirmado e os pontos reservados. Depois, use os IDs
exibidos para consultar [ranking e odds no Flask](reader.md). Resultado e
distribuição dos pontos são etapas posteriores.

## Preparar

1. As migrações V001–V005 devem estar aplicadas no projeto.
2. O `.env` deve conter a configuração já validada do banco e do Supabase Auth.
3. Tenha **duas contas diferentes** confirmadas no Supabase Auth. A primeira é o
   autor; a segunda é o participante. Se necessário, crie outra conta em
   **Authentication → Users → Add user → Create new user**, confirmada para teste.
4. Mantenha o Spring Boot em execução. Para iniciá-lo, da raiz do repositório:

```powershell
.\.venv\Scripts\python.exe scripts/writer.py bootRun
```

Se o serviço já estiver em execução na porta configurada, use a instância existente.
O roteiro acessa `127.0.0.1` na porta `GAMBLR_WRITER_PORT` do `.env` (padrão 8080).
Execute-o na máquina anfitriã. A demonstração em computadores distintos na rede
local ainda depende da interface ou de um cliente que acesse o anfitrião.

## Executar

Em outro terminal PowerShell, também na raiz:

```powershell
.\.venv\Scripts\python.exe scripts/demo_group_bet.py
```

Informe o email e a senha da primeira conta e, depois, os da segunda. As senhas
são digitadas sem eco. Senhas e tokens ficam apenas na memória do processo e não
são gravados nem mostrados na saída. Não coloque senhas nos argumentos do comando.

O script executa e confere estas operações:

1. Autentica as duas contas e provisiona/recupera seus perfis internos.
2. Confere que os perfis são diferentes antes de criar o grupo.
3. Cria um grupo público com entrada imediata e crédito inicial de 100 pontos.
4. Publica uma aposta binária, com término em 30 minutos e mínimo de 10 pontos.
5. Confere que o autor recebe uma rejeição ao tentar palpitar na própria proposta.
6. Faz o segundo usuário entrar **depois da publicação**, enquanto o prazo está aberto.
7. Confere sua carteira inicial: 100 disponíveis e 0 reservados.
8. Confirma um palpite de 30 pontos em **Sim** e consulta o próprio palpite.
9. Confere a carteira: 70 disponíveis e 30 reservados.
10. Repete a confirmação e exige `409 PREDICTION_EXISTS`, mantendo 70/30 na carteira.
11. Confere que o autor mantém 100 disponíveis e 0 reservados.
12. Encerra as duas sessões de teste no Auth ao final, inclusive em caso de falha.

Os valores e a pergunta são exemplos do roteiro, sem alterar as regras do produto.
As configurações de crédito diário e prazo de apuração/contestação também são
apenas dados do grupo; esses processamentos ainda não foram implementados.

## Resultado esperado

Além dos IDs do grupo, da aposta e do palpite, o terminal mostra:

```text
Autor impedido de palpitar na propria aposta: OK.
Segundo usuario entrou apos a publicacao da aposta.
Palpite duplicado rejeitado sem nova reserva: OK.
Participante: 100 -> 70 pontos disponiveis; 0 -> 30 pontos reservados.
Autor: 100 pontos disponiveis; 0 pontos reservados.
Demonstracao validada ate a reserva. Resultado e pagamento ainda nao implementados.
```

Os registros da demonstração ficam salvos em `gamblr`. Uma nova execução cria
outro grupo e outra aposta; os saldos são independentes por grupo. Os 30 pontos
continuam reservados: esta etapa ainda não implementa cancelamento, apuração ou
devolução automática após os prazos. Encerrar a sessão do Auth não desfaz o palpite.

Se houver erro de rede depois de uma escrita, não repita a confirmação cegamente.
O script não faz tentativas automáticas. Guarde os IDs já exibidos e confira o
estado do palpite e da carteira antes de repetir a operação. Não envie tokens
ou senhas ao relatar um erro.

## Verificação automatizada

Os testes Python conferem a orquestração e os caminhos de erro, incluindo contas
iguais, divergência de saldo e encerramento da primeira sessão se o segundo login
falhar. Execute com:

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s scripts/tests -v
```

O teste Java `terminalDemoExercisesRealHttpAndDatabase` executa o mesmo núcleo
Python contra o Spring e o PostgreSQL em schema temporário, usando JWTs assinados
pelo emissor local de teste. Execute pelo comando existente:

```powershell
.\.venv\Scripts\python.exe scripts/writer.py integration
```

Esse teste automatizado não usa nem cria contas reais no Supabase Auth. A execução
interativa acima valida a integração com as duas contas reais, no ambiente do projeto.
