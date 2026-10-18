-- T16: the employee directory also sorts by joining date ("Joined" column); V8 indexed only the name sort.
CREATE INDEX idx_employees_joining_date ON employees (joining_date);
