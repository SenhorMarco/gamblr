-- Dados e verificacoes executados dentro da transacao do verificador Python.
-- Todas as alteracoes, incluindo as tabelas, serao desfeitas por ROLLBACK.
DO $$
#variable_conflict use_variable
DECLARE
    author_id uuid := gen_random_uuid();
    ana_id uuid := gen_random_uuid();
    bruno_id uuid := gen_random_uuid();
    loser_id uuid;
    group_id uuid := gen_random_uuid();
    bet_id uuid := gen_random_uuid();
    other_bet_id uuid := gen_random_uuid();
    yes_id uuid := gen_random_uuid();
    no_id uuid := gen_random_uuid();
    empty_id uuid := gen_random_uuid();
    foreign_option_id uuid := gen_random_uuid();
    prediction_id uuid := gen_random_uuid();
    blocked boolean;
    i integer;
    object_name text;
BEGIN
    INSERT INTO gamblr.app_users(id, display_name, created_at) VALUES
        (author_id, 'Autor', '2026-01-01 00:00:00+00'),
        (ana_id, 'Ana', '2026-01-02 00:00:00+00'),
        (bruno_id, 'Bruno', '2026-01-03 00:00:00+00');
    INSERT INTO gamblr.groups(id, name, owner_id, initial_points, daily_points,
        settlement_period, contest_period, visibility, entry_mode, invite_policy)
    VALUES (group_id, 'Teste temporario', author_id, 100, 5,
        interval '1 hour', interval '1 hour', 'PRIVATE', 'INVITE', 'ALL_MEMBERS');
    INSERT INTO gamblr.memberships(group_id, user_id, role)
    VALUES (group_id, author_id, 'MEMBER'), (group_id, ana_id, 'MEMBER'),
        (group_id, bruno_id, 'MEMBER');
    INSERT INTO gamblr.wallets(group_id, user_id, available_points)
    VALUES (group_id, author_id, 100), (group_id, ana_id, 100), (group_id, bruno_id, 80);
    -- Forca a validacao da FK de propriedade antes do rollback final.
    SET CONSTRAINTS ALL IMMEDIATE;

    IF (SELECT user_id FROM gamblr.group_ranking r WHERE r.group_id = group_id AND position = 1) <> author_id THEN
        RAISE EXCEPTION 'Desempate por criacao da conta incorreto';
    END IF;
    UPDATE gamblr.wallets w SET available_points = 70, reserved_points = 30
        WHERE w.group_id = group_id AND user_id = ana_id;
    IF (SELECT position FROM gamblr.group_ranking r WHERE r.group_id = group_id AND user_id = ana_id)
        <= (SELECT position FROM gamblr.group_ranking r WHERE r.group_id = group_id AND user_id = bruno_id) THEN
        RAISE EXCEPTION 'Ranking inclui reserva indevidamente';
    END IF;
    UPDATE gamblr.wallets w SET available_points = 100, reserved_points = 0
        WHERE w.group_id = group_id AND user_id = ana_id;
    IF (SELECT position FROM gamblr.group_ranking r WHERE r.group_id = group_id AND user_id = ana_id)
        >= (SELECT position FROM gamblr.group_ranking r WHERE r.group_id = group_id AND user_id = bruno_id) THEN
        RAISE EXCEPTION 'Ranking nao refletiu devolucao';
    END IF;

    blocked := false;
    BEGIN
        UPDATE gamblr.wallets w SET available_points = -1
            WHERE w.group_id = group_id AND user_id = ana_id;
    EXCEPTION WHEN check_violation THEN blocked := true;
    END;
    IF NOT blocked THEN RAISE EXCEPTION 'Saldo negativo aceito'; END IF;

    INSERT INTO gamblr.bets(id, group_id, author_id, question, type, minimum_points,
        published_at, ends_at, predictions_close_at, author_cancel_until,
        settlement_period, contest_period)
    VALUES (bet_id, group_id, author_id, 'Teste?', 'MULTIPLE_CHOICE', 10,
        '2026-11-13 10:00Z', '2026-11-13 20:00Z', '2026-11-13 19:00Z',
        '2026-11-13 11:00Z', interval '1 hour', interval '1 hour'),
        (other_bet_id, group_id, author_id, 'Outra?', 'BINARY', 10,
        '2026-11-13 10:00Z', '2026-11-13 20:00Z', '2026-11-13 19:00Z',
        '2026-11-13 11:00Z', interval '1 hour', interval '1 hour');
    INSERT INTO gamblr.bet_options(id, bet_id, label, position) VALUES
        (yes_id, bet_id, 'Sim', 0), (no_id, bet_id, 'Nao', 1),
        (empty_id, bet_id, 'Outra', 2), (foreign_option_id, other_bet_id, 'Sim', 0);
    INSERT INTO gamblr.predictions(id, bet_id, group_id, user_id, option_id, points)
    VALUES (prediction_id, bet_id, group_id, ana_id, yes_id, 10),
        (gen_random_uuid(), bet_id, group_id, bruno_id, yes_id, 30);
    FOR i IN 1..3 LOOP
        loser_id := gen_random_uuid();
        INSERT INTO gamblr.app_users(id, display_name) VALUES (loser_id, 'Participante');
        INSERT INTO gamblr.memberships(group_id, user_id, role) VALUES (group_id, loser_id, 'MEMBER');
        INSERT INTO gamblr.wallets(group_id, user_id) VALUES (group_id, loser_id);
        INSERT INTO gamblr.predictions(bet_id, group_id, user_id, option_id, points)
        VALUES (bet_id, group_id, loser_id, no_id, CASE WHEN i = 1 THEN 20 ELSE 30 END);
    END LOOP;

    IF (SELECT decimal_odds FROM gamblr.bet_odds WHERE option_id = yes_id) <> 2.5 THEN
        RAISE EXCEPTION 'Odd deveria ser 2.50 por votos';
    END IF;
    IF (SELECT decimal_odds FROM gamblr.bet_odds WHERE option_id = empty_id) IS NOT NULL THEN
        RAISE EXCEPTION 'Opcao sem votos deveria ter odd NULL';
    END IF;
    blocked := false;
    BEGIN
        INSERT INTO gamblr.predictions(bet_id, group_id, user_id, option_id, points)
        VALUES (bet_id, group_id, ana_id, yes_id, 10);
    EXCEPTION WHEN unique_violation THEN blocked := true;
    END;
    IF NOT blocked THEN RAISE EXCEPTION 'Palpite duplicado aceito'; END IF;
    blocked := false;
    BEGIN
        INSERT INTO gamblr.predictions(bet_id, group_id, user_id, option_id, points)
        VALUES (bet_id, group_id, author_id, foreign_option_id, 10);
    EXCEPTION WHEN foreign_key_violation THEN blocked := true;
    END;
    IF NOT blocked THEN RAISE EXCEPTION 'Opcao de outra aposta aceita'; END IF;

    INSERT INTO gamblr.point_movements(group_id, user_id, kind, available_delta,
        reserved_delta, operation_key)
    VALUES (group_id, ana_id, 'INITIAL_CREDIT', 100, 0, 'initial-1');
    blocked := false;
    BEGIN
        INSERT INTO gamblr.point_movements(group_id, user_id, kind, available_delta,
            reserved_delta, operation_key)
        VALUES (group_id, ana_id, 'INITIAL_CREDIT', 100, 0, 'initial-2');
    EXCEPTION WHEN unique_violation THEN blocked := true;
    END;
    IF NOT blocked THEN RAISE EXCEPTION 'Credito inicial duplicado aceito'; END IF;

    INSERT INTO gamblr.point_movements(group_id, user_id, kind, available_delta,
        reserved_delta, credit_date, operation_key)
    VALUES (group_id, ana_id, 'DAILY_CREDIT', 5, 0, '2026-11-13', 'daily-1');
    blocked := false;
    BEGIN
        INSERT INTO gamblr.point_movements(group_id, user_id, kind, available_delta,
            reserved_delta, credit_date, operation_key)
        VALUES (group_id, ana_id, 'DAILY_CREDIT', 5, 0, '2026-11-13', 'daily-2');
    EXCEPTION WHEN unique_violation THEN blocked := true;
    END;
    IF NOT blocked THEN RAISE EXCEPTION 'Credito diario duplicado aceito'; END IF;

    INSERT INTO gamblr.point_movements(group_id, user_id, prediction_id, kind,
        available_delta, reserved_delta, operation_key)
    VALUES (group_id, ana_id, prediction_id, 'REFUND', 10, -10, 'refund-1');
    blocked := false;
    BEGIN
        INSERT INTO gamblr.point_movements(group_id, user_id, prediction_id, kind,
            available_delta, reserved_delta, operation_key)
        VALUES (group_id, ana_id, prediction_id, 'PAYOUT', 30, -10, 'payout-1');
    EXCEPTION WHEN unique_violation THEN blocked := true;
    END;
    IF NOT blocked THEN RAISE EXCEPTION 'Segunda conclusao do palpite aceita'; END IF;

    UPDATE gamblr.memberships m SET left_at = now() WHERE m.group_id = group_id AND user_id = ana_id;
    IF EXISTS (SELECT 1 FROM gamblr.group_ranking r WHERE r.group_id = group_id AND user_id = ana_id) THEN
        RAISE EXCEPTION 'Ex-integrante continua no ranking';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM gamblr.wallets w WHERE w.group_id = group_id AND user_id = ana_id) THEN
        RAISE EXCEPTION 'Carteira perdida na saida';
    END IF;

    FOREACH object_name IN ARRAY ARRAY['app_users', 'groups', 'memberships', 'wallets',
        'bets', 'bet_options', 'predictions', 'point_movements', 'contests', 'contest_decisions'] LOOP
        IF has_table_privilege('gamblr_reader', 'gamblr.' || object_name, 'SELECT')
            OR has_table_privilege('gamblr_reader', 'gamblr.' || object_name, 'INSERT,UPDATE,DELETE,TRUNCATE') THEN
            RAISE EXCEPTION 'Leitor tem acesso a tabela privada';
        END IF;
        IF NOT has_table_privilege('gamblr_writer', 'gamblr.' || object_name, 'SELECT') THEN
            RAISE EXCEPTION 'Writer sem acesso de leitura';
        END IF;
    END LOOP;
    FOREACH object_name IN ARRAY ARRAY['current_group_members', 'group_ranking', 'bet_odds'] LOOP
        IF NOT has_table_privilege('gamblr_reader', 'gamblr.' || object_name, 'SELECT') THEN
            RAISE EXCEPTION 'Leitor sem acesso a view';
        END IF;
    END LOOP;
    IF has_table_privilege('gamblr_writer', 'gamblr.predictions', 'UPDATE,DELETE') OR
        has_table_privilege('gamblr_writer', 'gamblr.point_movements', 'UPDATE,DELETE') THEN
        RAISE EXCEPTION 'Writer pode alterar historico definitivo';
    END IF;
END $$;
