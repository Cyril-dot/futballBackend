-- Allow Hibernate to pass VARCHAR and have Postgres auto-cast
CREATE CAST (varchar AS user_role)   WITH INOUT AS IMPLICIT;
CREATE CAST (varchar AS user_status) WITH INOUT AS IMPLICIT;