-- Realtime, the server half (docs/adr/0002-supabase-sync.md, decision 12).
--
-- Two changes and nothing else: both tables join the `supabase_realtime` publication, and both
-- get `replica identity full`. The client half is one channel filtered on `user_id`, whose
-- payloads merge through the same last-writer-wins rule as a pull and never advance the cursor.
--
-- Idempotent on purpose, the same as the first migration: this file is applied with `supabase db
-- push` *or* pasted into the SQL editor of a project that may already carry half of it, and
-- `alter publication ... add table` is an error rather than a no-op for a table already in it.

-- Supabase creates this publication with every project, but a database restored from elsewhere
-- may not have it, and an absent publication would make the two statements below fail with a
-- message about realtime rather than about the publication.
do $$
begin
    if not exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
        create publication supabase_realtime;
    end if;

    if not exists (
        select 1 from pg_publication_tables
        where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'tasks'
    ) then
        alter publication supabase_realtime add table public.tasks;
    end if;

    if not exists (
        select 1 from pg_publication_tables
        where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'projects'
    ) then
        alter publication supabase_realtime add table public.projects;
    end if;
end
$$;

-- `full`, not the default `default` (primary key only): Realtime re-checks RLS against the row it
-- is about to deliver, and for an UPDATE it needs every column to do that — with the default
-- replica identity the old row carries the key alone, the check cannot be made, and the update is
-- dropped. Every delete in Cadence is a tombstone UPDATE (decision 3), so that would silently
-- drop precisely the events a deletion travels on.
alter table public.tasks replica identity full;
alter table public.projects replica identity full;
