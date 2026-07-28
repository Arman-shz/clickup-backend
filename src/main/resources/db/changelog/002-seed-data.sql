--liquibase formatted sql

-- Demo data: 30 rows per content table, in Persian.
--
-- Every changeset here is tagged `contextFilter:seed`, and only the dev and test
-- profiles activate that context. Production runs the schema changelog and stops --
-- it must not inherit thirty accounts whose password is published in this file.
--
-- refresh_tokens is deliberately not seeded. A refresh token is a live credential
-- with an expiry; thirty pre-minted ones would be either already expired or thirty
-- standing logins, and neither is useful test data.
--
-- All seeded accounts share one bcrypt hash, of the password the spec's own login
-- example uses: studentId 99100111 / password Password123 (usr_101, a student).
-- usr_102 is the equivalent admin account.

--changeset arman:100-seed-users contextFilter:seed
--comment: 30 accounts. usr_101 reproduces the spec's login example exactly.
INSERT INTO users (id, student_id, name, email, password_hash,
                   role, avatar, theme, language, notifications_enabled, status)
SELECT v.id, v.student_id, v.name, v.email,
       '$2a$10$xGMgwd8fXYtou3WWprfU4.0BwYffUsIYgrvJDOmRpFMZamzjSBYuO',
       v.role, v.avatar, v.theme, v.language, v.notifications_enabled, v.status
FROM (VALUES
    ('usr_101', '99100111', 'علی محمدی',    'a.mohammadi@example.com', 'student', 'https://cdn.example.com/avatars/user101.jpg', 'light', 'fa', TRUE,  'active'),
    ('usr_102', '99100112', 'زهرا حسینی',   'z.hosseini@example.com',  'admin',   'https://cdn.example.com/avatars/user102.jpg', 'dark',  'fa', TRUE,  'active'),
    ('usr_103', '99100113', 'محمد رضایی',   'm.rezaei@example.com',    'student', 'https://cdn.example.com/avatars/user103.jpg', 'light', 'fa', FALSE, 'active'),
    ('usr_104', '99100114', 'فاطمه کریمی',  'f.karimi@example.com',    'admin',   'https://cdn.example.com/avatars/user104.jpg', 'dark',  'fa', TRUE,  'active'),
    ('usr_105', '99100115', 'امیر احمدی',   'a.ahmadi@example.com',    'student', 'https://cdn.example.com/avatars/user105.jpg', 'light', 'en', TRUE,  'active'),
    ('usr_106', '99100116', 'نرگس موسوی',   'n.mousavi@example.com',   'student', 'https://cdn.example.com/avatars/user106.jpg', 'dark',  'fa', TRUE,  'active'),
    ('usr_107', '99100117', 'حسین نوری',    'h.nouri@example.com',     'student', 'https://cdn.example.com/avatars/user107.jpg', 'light', 'fa', FALSE, 'active'),
    ('usr_108', '99100118', 'مریم صادقی',   'm.sadeghi@example.com',   'student', 'https://cdn.example.com/avatars/user108.jpg', 'dark',  'fa', TRUE,  'active'),
    ('usr_109', '99100119', 'رضا جعفری',    'r.jafari@example.com',    'student', 'https://cdn.example.com/avatars/user109.jpg', 'light', 'fa', TRUE,  'active'),
    ('usr_110', '99100120', 'سارا اکبری',   's.akbari@example.com',    'student', 'https://cdn.example.com/avatars/user110.jpg', 'dark',  'fa', FALSE, 'active'),
    ('usr_111', '99100121', 'مهدی قاسمی',   'm.ghasemi@example.com',   'student', 'https://cdn.example.com/avatars/user111.jpg', 'light', 'fa', TRUE,  'active'),
    ('usr_112', '99100122', 'الهام رستمی',  'e.rostami@example.com',   'student', 'https://cdn.example.com/avatars/user112.jpg', 'dark',  'fa', TRUE,  'active'),
    ('usr_113', '99100123', 'سعید عباسی',   's.abbasi@example.com',    'student', 'https://cdn.example.com/avatars/user113.jpg', 'light', 'fa', TRUE,  'active'),
    ('usr_114', '99100124', 'نیلوفر شریفی', 'n.sharifi@example.com',   'student', 'https://cdn.example.com/avatars/user114.jpg', 'dark',  'fa', FALSE, 'active'),
    ('usr_115', '99100125', 'حمید یوسفی',   'h.yousefi@example.com',   'student', 'https://cdn.example.com/avatars/user115.jpg', 'light', 'fa', TRUE,  'active'),
    ('usr_116', '99100126', 'پریسا بهرامی', 'p.bahrami@example.com',   'student', 'https://cdn.example.com/avatars/user116.jpg', 'dark',  'fa', TRUE,  'active'),
    ('usr_117', '99100127', 'کاوه اسدی',    'k.asadi@example.com',     'student', 'https://cdn.example.com/avatars/user117.jpg', 'light', 'en', TRUE,  'active'),
    ('usr_118', '99100128', 'شیرین طاهری',  'sh.taheri@example.com',   'student', 'https://cdn.example.com/avatars/user118.jpg', 'dark',  'fa', FALSE, 'active'),
    ('usr_119', '99100129', 'بهزاد مرادی',  'b.moradi@example.com',    'student', 'https://cdn.example.com/avatars/user119.jpg', 'light', 'fa', TRUE,  'inactive'),
    ('usr_120', '99100130', 'لیلا زارعی',   'l.zarei@example.com',     'student', 'https://cdn.example.com/avatars/user120.jpg', 'dark',  'fa', TRUE,  'active'),
    ('usr_121', '99100131', 'آرش نجفی',     'a.najafi@example.com',    'student', 'https://cdn.example.com/avatars/user121.jpg', 'light', 'fa', TRUE,  'active'),
    ('usr_122', '99100132', 'مینا سلطانی',  'm.soltani@example.com',   'student', 'https://cdn.example.com/avatars/user122.jpg', 'dark',  'fa', FALSE, 'active'),
    ('usr_123', '99100133', 'فرهاد کاظمی',  'f.kazemi@example.com',    'student', 'https://cdn.example.com/avatars/user123.jpg', 'light', 'fa', TRUE,  'active'),
    ('usr_124', '99100134', 'سمیرا خسروی',  's.khosravi@example.com',  'student', 'https://cdn.example.com/avatars/user124.jpg', 'dark',  'fa', TRUE,  'active'),
    ('usr_125', '99100135', 'پویا امینی',   'p.amini@example.com',     'student', 'https://cdn.example.com/avatars/user125.jpg', 'light', 'en', TRUE,  'active'),
    ('usr_126', '99100136', 'هانیه غفاری',  'h.ghaffari@example.com',  'student', 'https://cdn.example.com/avatars/user126.jpg', 'dark',  'fa', FALSE, 'active'),
    ('usr_127', '99100137', 'بابک شاهی',    'b.shahi@example.com',     'student', 'https://cdn.example.com/avatars/user127.jpg', 'light', 'fa', TRUE,  'inactive'),
    ('usr_128', '99100138', 'رویا فتحی',    'r.fathi@example.com',     'student', 'https://cdn.example.com/avatars/user128.jpg', 'dark',  'fa', TRUE,  'active'),
    ('usr_129', '99100139', 'سینا ملکی',    's.maleki@example.com',    'student', 'https://cdn.example.com/avatars/user129.jpg', 'light', 'fa', TRUE,  'active'),
    ('usr_130', '99100140', 'آیدا حیدری',   'a.heydari@example.com',   'student', 'https://cdn.example.com/avatars/user130.jpg', 'dark',  'fa', TRUE,  'active')
) AS v (id, student_id, name, email, role, avatar, theme, language, notifications_enabled, status);

SELECT setval('users_id_seq', 130, TRUE);
--rollback DELETE FROM users;

--changeset arman:101-seed-projects contextFilter:seed
--comment: 30 projects. proj_1 reproduces the spec's create-project example.
INSERT INTO projects (id, title, description, color, icon) VALUES
    ('proj_1',  'طراحی سیستم مدیریت هوشمند',   'پروژه فاز اول سامانه ClickUp',                '#3B82F6', 'FolderKanban'),
    ('proj_2',  'بازطراحی رابط کاربری داشبورد', 'یکپارچه‌سازی کامپوننت‌ها و افزودن تم تاریک',  '#8B5CF6', 'LayoutDashboard'),
    ('proj_3',  'سامانه احراز هویت متمرکز',    'پیاده‌سازی توکن JWT و مدیریت نشست‌ها',        '#10B981', 'ShieldCheck'),
    ('proj_4',  'موتور گزارش‌گیری هفتگی',       'تجمیع کارکرد اعضا و خروجی اکسل',              '#F59E0B', 'FileBarChart'),
    ('proj_5',  'زیرساخت چت زنده',             'انتقال لحظه‌ای پیام‌ها روی SSE',               '#EF4444', 'MessagesSquare'),
    ('proj_6',  'مهاجرت پایگاه داده به پستگرس', 'انتقال داده‌ها و بازنویسی کوئری‌ها',          '#06B6D4', 'Database'),
    ('proj_7',  'خودکارسازی آزمون‌های سرتاسری', 'پوشش سناریوهای بحرانی کاربر',                 '#6366F1', 'TestTube'),
    ('proj_8',  'بهینه‌سازی کارایی صفحات',      'کاهش زمان بارگذاری اولیه',                    '#84CC16', 'Gauge'),
    ('proj_9',  'سامانه آپلود فایل ابری',      'اتصال به فضای ذخیره‌سازی شیءگرا',              '#EC4899', 'CloudUpload'),
    ('proj_10', 'جست‌وجوی پیشرفته تسک‌ها',      'فیلترهای ترکیبی روی وضعیت و پروژه',            '#14B8A6', 'Search'),
    ('proj_11', 'اپلیکیشن موبایل نسخه اول',    'نسخه اندروید با ری‌اکت نیتیو',                 '#F97316', 'Smartphone'),
    ('proj_12', 'مستندسازی فنی پروژه',         'تدوین راهنمای توسعه‌دهندگان',                  '#64748B', 'BookOpen'),
    ('proj_13', 'داشبورد آنالیتیکس مدیریت',    'نمودارهای بلادرنگ عملکرد تیم',                '#A855F7', 'ChartLine'),
    ('proj_14', 'اعلان‌های درون‌برنامه‌ای',      'مدیریت اعلان و تنظیمات کاربر',                '#0EA5E9', 'Bell'),
    ('proj_15', 'یکپارچه‌سازی تقویم شمسی',      'نمایش سررسید تسک‌ها به تاریخ جلالی',           '#22C55E', 'CalendarDays'),
    ('proj_16', 'بازسازی معماری سرویس‌ها',      'تفکیک لایه‌ها و کاهش وابستگی',                 '#E11D48', 'Boxes'),
    ('proj_17', 'پنل مدیریت کاربران',          'مدیریت نقش‌ها و سطوح دسترسی',                  '#7C3AED', 'UsersRound'),
    ('proj_18', 'راه‌اندازی خط لوله استقرار',   'ساخت و انتشار خودکار نسخه‌ها',                 '#0891B2', 'Workflow'),
    ('proj_19', 'پشتیبانی از چندزبانگی',       'افزودن زبان انگلیسی به رابط کاربری',          '#CA8A04', 'Languages'),
    ('proj_20', 'ممیزی امنیتی سامانه',         'بررسی آسیب‌پذیری‌های رایج وب',                 '#DC2626', 'Lock'),
    ('proj_21', 'سامانه لاگ متمرکز',           'جمع‌آوری خطاهای سمت کلاینت',                   '#475569', 'ScrollText'),
    ('proj_22', 'بهبود دسترس‌پذیری رابط',       'رعایت استانداردهای دسترس‌پذیری',               '#059669', 'Accessibility'),
    ('proj_23', 'کش‌گذاری پاسخ‌های پرتکرار',     'کاهش بار روی پایگاه داده',                     '#D97706', 'Zap'),
    ('proj_24', 'طراحی سیستم دیزاین',          'کتابخانه کامپوننت‌های مشترک',                  '#DB2777', 'Palette'),
    ('proj_25', 'مدیریت نسخه‌های انتشار',       'زمان‌بندی و تدوین یادداشت انتشار',             '#2563EB', 'GitBranch'),
    ('proj_26', 'پشتیبان‌گیری خودکار داده‌ها',   'زمان‌بندی روزانه و آزمون بازیابی',             '#16A34A', 'HardDriveDownload'),
    ('proj_27', 'ساخت تصویر بومی گرال‌وی‌ام',    'کاهش زمان راه‌اندازی سرویس',                   '#9333EA', 'Rocket'),
    ('proj_28', 'پایش سلامت سرویس‌ها',          'بررسی وضعیت و هشداردهی خودکار',                '#0D9488', 'HeartPulse'),
    ('proj_29', 'بازنویسی موتور جست‌وجو',       'نمایه‌سازی صحیح متن فارسی',                    '#B45309', 'FileSearch'),
    ('proj_30', 'آموزش اعضای جدید تیم',        'تدوین مسیر یادگیری داخلی',                     '#4F46E5', 'GraduationCap');

SELECT setval('projects_id_seq', 30, TRUE);
--rollback DELETE FROM projects;

--changeset arman:102-seed-tasks contextFilter:seed
--comment: 30 tasks spread over the projects, covering every status and priority.
--comment: task_1 reproduces the spec's create-task example.
INSERT INTO tasks (id, project_id, title, description, status, priority, due_date) VALUES
    ('task_1',  'proj_1',  'پیاده‌سازی صفحه ورود',              'طراحی فرم و اتصال به API',                  'in_progress', 'high',   DATE '2026-08-15'),
    ('task_2',  'proj_1',  'تعریف ساختار پایگاه داده',          'جدول‌ها، کلیدهای خارجی و نمایه‌ها',          'done',        'high',   DATE '2026-08-05'),
    ('task_3',  'proj_1',  'نگارش مستندات API',                 'تکمیل و بازبینی فایل swagger',              'todo',        'medium', DATE '2026-09-01'),
    ('task_4',  'proj_2',  'طراحی تم تاریک',                    'تعریف متغیرهای رنگ و آزمون کنتراست',        'done',        'medium', DATE '2026-08-02'),
    ('task_5',  'proj_2',  'یکپارچه‌سازی کامپوننت دکمه',         'حذف نسخه‌های تکراری از رابط',               'review',      'low',    DATE '2026-08-20'),
    ('task_6',  'proj_3',  'پیاده‌سازی صدور توکن دسترسی',        'امضا و اعتبارسنجی توکن JWT',                'in_progress', 'high',   DATE '2026-08-12'),
    ('task_7',  'proj_3',  'مدیریت توکن رفرش',                  'ذخیره، تمدید و ابطال توکن‌ها',              'todo',        'high',   DATE '2026-08-25'),
    ('task_8',  'proj_4',  'تجمیع ساعات کارکرد اعضا',           'محاسبه جمع ساعات در بازه هفتگی',            'todo',        'medium', DATE '2026-09-05'),
    ('task_9',  'proj_4',  'خروجی اکسل گزارش‌ها',                'تولید فایل با ستون‌های فارسی',              'todo',        'low',    DATE '2026-09-15'),
    ('task_10', 'proj_5',  'برقراری اتصال استریم',              'نگه‌داشتن اتصال بلندمدت SSE',               'in_progress', 'high',   DATE '2026-08-18'),
    ('task_11', 'proj_5',  'پخش پیام به کلاینت‌های متصل',        'انتشار همزمان پیام جدید برای همه',          'todo',        'high',   DATE '2026-08-22'),
    ('task_12', 'proj_6',  'انتقال داده‌های قدیمی',              'نگاشت ستون‌ها و اعتبارسنجی نتیجه',          'done',        'high',   DATE '2026-07-20'),
    ('task_13', 'proj_6',  'تنظیم ترتیب حروف فارسی',            'استفاده از ICU برای مرتب‌سازی درست',        'done',        'medium', DATE '2026-07-25'),
    ('task_14', 'proj_7',  'نوشتن سناریوی ورود کاربر',          'پوشش مسیر موفق و ناموفق',                   'review',      'medium', DATE '2026-08-28'),
    ('task_15', 'proj_8',  'کاهش حجم بسته جاوااسکریپت',         'تفکیک کد بر اساس مسیر',                     'todo',        'medium', DATE '2026-09-10'),
    ('task_16', 'proj_9',  'محدودسازی حجم آپلود',               'اعمال سقف ۵۰ مگابایت و پیام خطای مناسب',    'todo',        'high',   DATE '2026-08-30'),
    ('task_17', 'proj_9',  'تولید متادیتای ابری',               'ساخت کلید شیء و نشانی CDN',                 'todo',        'low',    DATE '2026-09-08'),
    ('task_18', 'proj_10', 'فیلتر تسک‌ها بر اساس وضعیت',         'افزودن پارامترهای پرس‌وجو به فهرست',         'done',        'medium', DATE '2026-08-08'),
    ('task_19', 'proj_11', 'راه‌اندازی پروژه ری‌اکت نیتیو',       'پیکربندی اولیه و ناوبری میان صفحات',        'in_progress', 'medium', DATE '2026-09-20'),
    ('task_20', 'proj_12', 'نگارش راهنمای راه‌اندازی',           'مراحل اجرای پروژه روی سیستم تازه',          'review',      'low',    DATE '2026-08-14'),
    ('task_21', 'proj_13', 'نمودار پیشرفت پروژه‌ها',             'نمایش درصد تکمیل هر پروژه',                 'todo',        'medium', DATE '2026-09-25'),
    ('task_22', 'proj_14', 'تنظیمات اعلان کاربر',               'فعال و غیرفعال‌سازی اعلان‌ها',               'done',        'low',    DATE '2026-08-01'),
    ('task_23', 'proj_15', 'تبدیل تاریخ میلادی به جلالی',       'نمایش سررسید تسک‌ها به شمسی',               'in_progress', 'medium', DATE '2026-08-27'),
    ('task_24', 'proj_16', 'تفکیک لایه سرویس از منبع',          'خارج کردن منطق کسب‌وکار از لایه REST',       'todo',        'high',   DATE '2026-09-12'),
    ('task_25', 'proj_17', 'تعریف نقش مدیر و دانشجو',           'اعمال کنترل دسترسی بر پایه نقش',            'in_progress', 'high',   DATE '2026-08-19'),
    ('task_26', 'proj_18', 'ساخت خودکار در هر ادغام',           'اجرای آزمون‌ها پیش از ادغام شاخه',          'done',        'medium', DATE '2026-07-30'),
    ('task_27', 'proj_20', 'بررسی تزریق SQL',                   'ممیزی کوئری‌های پویا و پارامترها',          'review',      'high',   DATE '2026-09-03'),
    ('task_28', 'proj_21', 'دریافت لاگ خطای فرانت‌اند',          'ثبت خطاهای کلاینت در فایل متمرکز',          'todo',        'medium', DATE '2026-09-18'),
    ('task_29', 'proj_27', 'ساخت تصویر بومی',                   'کامپایل با گرال‌وی‌ام ۲۵ و سنجش زمان اجرا',  'todo',        'high',   DATE '2026-10-01'),
    ('task_30', 'proj_28', 'افزودن بررسی سلامت پایگاه داده',    'پاسخ‌گویی به کاوشگر آمادگی سرویس',          'done',        'medium', DATE '2026-07-28');

SELECT setval('tasks_id_seq', 30, TRUE);
--rollback DELETE FROM tasks;

--changeset arman:103-seed-task-assignees contextFilter:seed
--comment: One to three assignees per task. task_1 -> usr_101 is the spec's example.
INSERT INTO task_assignees (task_id, user_id) VALUES
    ('task_1',  'usr_101'), ('task_1',  'usr_103'),
    ('task_2',  'usr_105'),
    ('task_3',  'usr_102'), ('task_3',  'usr_107'),
    ('task_4',  'usr_106'),
    ('task_5',  'usr_108'), ('task_5',  'usr_110'),
    ('task_6',  'usr_101'), ('task_6',  'usr_109'), ('task_6', 'usr_111'),
    ('task_7',  'usr_109'),
    ('task_8',  'usr_112'), ('task_8',  'usr_114'),
    ('task_9',  'usr_113'),
    ('task_10', 'usr_103'), ('task_10', 'usr_115'),
    ('task_11', 'usr_115'),
    ('task_12', 'usr_116'), ('task_12', 'usr_118'),
    ('task_13', 'usr_117'),
    ('task_14', 'usr_119'), ('task_14', 'usr_120'),
    ('task_15', 'usr_121'),
    ('task_16', 'usr_122'), ('task_16', 'usr_124'),
    ('task_17', 'usr_123'),
    ('task_18', 'usr_125'), ('task_18', 'usr_126'),
    ('task_19', 'usr_127'),
    ('task_20', 'usr_128'), ('task_20', 'usr_130'),
    ('task_21', 'usr_129'),
    ('task_22', 'usr_104'), ('task_22', 'usr_106'),
    ('task_23', 'usr_105'),
    ('task_24', 'usr_102'), ('task_24', 'usr_108'), ('task_24', 'usr_112'),
    ('task_25', 'usr_104'),
    ('task_26', 'usr_107'), ('task_26', 'usr_113'),
    ('task_27', 'usr_110'),
    ('task_28', 'usr_114'), ('task_28', 'usr_120'),
    ('task_29', 'usr_111'), ('task_29', 'usr_117'),
    ('task_30', 'usr_101'), ('task_30', 'usr_102'), ('task_30', 'usr_118');
--rollback DELETE FROM task_assignees;

--changeset arman:104-seed-weekly-reports contextFilter:seed
--comment: One report per user. user_name is read from users rather than repeated here,
--comment: so the snapshot cannot drift from the account it belongs to.
--comment: rep_1 reproduces the spec's create-report example.
INSERT INTO weekly_reports (id, user_id, user_name, week_title, hours_worked,
                            tasks_completed, achievements, challenges, next_week_plan, submitted_at)
SELECT v.id, v.user_id, u.name, v.week_title, v.hours_worked,
       v.tasks_completed, v.achievements, v.challenges, v.next_week_plan, v.submitted_at
FROM (VALUES
    ('rep_1',  'usr_101', 'هفته دوم مرداد ۱۴۰۵',    42.0, 8, 'تکمیل بخش ورود کاربران و تست‌های اتوماسیون E2E', 'پیچیدگی هماهنگی SSE روی سرور ابری',        'طراحی داشبورد آنالیتیکس مدیریت',       TIMESTAMPTZ '2026-08-08 17:30:00+03:30'),
    ('rep_2',  'usr_102', 'هفته دوم مرداد ۱۴۰۵',    38.5, 6, 'بازبینی معماری لایه سرویس',                      'کمبود مستندات نسخه قبلی',                  'شروع تفکیک ماژول گزارش‌گیری',           TIMESTAMPTZ '2026-08-08 18:05:00+03:30'),
    ('rep_3',  'usr_103', 'هفته سوم مرداد ۱۴۰۵',    45.0, 9, 'اتصال کامل فرم ورود به سرویس احراز هویت',        'ناپایداری شبکه هنگام آزمون',               'پیاده‌سازی صفحه بازیابی نشست',          TIMESTAMPTZ '2026-08-15 16:45:00+03:30'),
    ('rep_4',  'usr_104', 'هفته سوم مرداد ۱۴۰۵',    40.0, 7, 'تعریف نقش‌ها و سطوح دسترسی',                      'ابهام در تفکیک دسترسی مدیر و دانشجو',      'اعمال کنترل دسترسی روی مسیرها',        TIMESTAMPTZ '2026-08-15 19:10:00+03:30'),
    ('rep_5',  'usr_105', 'هفته چهارم مرداد ۱۴۰۵',  36.0, 5, 'کاهش زمان بارگذاری صفحه اصلی',                    'حجم زیاد کتابخانه‌های جانبی',              'تفکیک کد بر اساس مسیر',                TIMESTAMPTZ '2026-08-22 15:20:00+03:30'),
    ('rep_6',  'usr_106', 'هفته چهارم مرداد ۱۴۰۵',  44.5, 10, 'تکمیل تم تاریک و آزمون کنتراست',                'هماهنگی رنگ‌ها با سیستم دیزاین',           'یکپارچه‌سازی کامپوننت‌های تکراری',       TIMESTAMPTZ '2026-08-22 17:00:00+03:30'),
    ('rep_7',  'usr_107', 'هفته اول شهریور ۱۴۰۵',   39.0, 6, 'نگارش پیش‌نویس مستندات API',                      'تغییرات پیاپی در قرارداد سرویس',           'بازبینی نهایی فایل swagger',           TIMESTAMPTZ '2026-08-29 16:30:00+03:30'),
    ('rep_8',  'usr_108', 'هفته اول شهریور ۱۴۰۵',   41.5, 8, 'بازبینی کامپوننت دکمه در کل رابط',                'وابستگی به نسخه قدیمی کتابخانه',           'انتشار نسخه اول سیستم دیزاین',         TIMESTAMPTZ '2026-08-29 18:40:00+03:30'),
    ('rep_9',  'usr_109', 'هفته دوم شهریور ۱۴۰۵',   47.0, 11, 'پیاده‌سازی صدور و اعتبارسنجی توکن',             'مدیریت انقضای همزمان دو نوع توکن',         'افزودن ابطال توکن رفرش',               TIMESTAMPTZ '2026-09-05 17:15:00+03:30'),
    ('rep_10', 'usr_110', 'هفته دوم شهریور ۱۴۰۵',   35.0, 5, 'ممیزی کوئری‌های پویا',                            'یافتن نقاط الحاق رشته در کوئری‌ها',        'جایگزینی با کوئری پارامتری',           TIMESTAMPTZ '2026-09-05 19:25:00+03:30'),
    ('rep_11', 'usr_111', 'هفته سوم شهریور ۱۴۰۵',   43.0, 9, 'راه‌اندازی ساخت تصویر بومی',                      'زمان طولانی کامپایل گرال‌وی‌ام',            'سنجش زمان راه‌اندازی سرویس',            TIMESTAMPTZ '2026-09-12 16:00:00+03:30'),
    ('rep_12', 'usr_112', 'هفته سوم شهریور ۱۴۰۵',   37.5, 6, 'تجمیع ساعات کارکرد اعضا',                        'تفاوت مبنای محاسبه هفته شمسی و میلادی',    'افزودن خروجی اکسل گزارش‌ها',            TIMESTAMPTZ '2026-09-12 18:50:00+03:30'),
    ('rep_13', 'usr_113', 'هفته چهارم شهریور ۱۴۰۵', 40.5, 7, 'طراحی قالب خروجی اکسل',                          'راست‌چین شدن ستون‌های فارسی',               'تولید فایل نهایی گزارش',               TIMESTAMPTZ '2026-09-19 17:40:00+03:30'),
    ('rep_14', 'usr_114', 'هفته چهارم شهریور ۱۴۰۵', 33.0, 4, 'بازبینی فیلترهای فهرست تسک',                     'حجم بالای داده در آزمون',                  'افزودن نمایه روی ستون وضعیت',          TIMESTAMPTZ '2026-09-19 15:55:00+03:30'),
    ('rep_15', 'usr_115', 'هفته اول مهر ۱۴۰۵',      46.0, 10, 'برقراری پایدار اتصال استریم',                   'قطع اتصال پشت پراکسی معکوس',               'پخش پیام به همه کلاینت‌ها',             TIMESTAMPTZ '2026-09-26 18:20:00+03:30'),
    ('rep_16', 'usr_116', 'هفته اول مهر ۱۴۰۵',      38.0, 6, 'انتقال داده‌های قدیمی به پستگرس',                 'ناسازگاری نوع داده در چند ستون',           'اعتبارسنجی کامل داده منتقل‌شده',        TIMESTAMPTZ '2026-09-26 16:35:00+03:30'),
    ('rep_17', 'usr_117', 'هفته دوم مهر ۱۴۰۵',      41.0, 8, 'تنظیم ترتیب درست حروف فارسی',                    'رفتار متفاوت مرتب‌سازی در محیط توسعه',     'مستندسازی تنظیمات ICU',                TIMESTAMPTZ '2026-10-03 17:05:00+03:30'),
    ('rep_18', 'usr_118', 'هفته دوم مهر ۱۴۰۵',      34.5, 5, 'بررسی دسترس‌پذیری فرم‌ها',                        'نبود برچسب مناسب روی ورودی‌ها',            'اصلاح ترتیب پیمایش با صفحه‌کلید',       TIMESTAMPTZ '2026-10-03 19:00:00+03:30'),
    ('rep_19', 'usr_119', 'هفته سوم مهر ۱۴۰۵',      29.0, 3, 'تکمیل سناریوی ورود کاربر',                       'ناپایداری آزمون‌ها روی سرور ساخت',         'پایدارسازی آزمون‌های سرتاسری',          TIMESTAMPTZ '2026-10-10 15:30:00+03:30'),
    ('rep_20', 'usr_120', 'هفته سوم مهر ۱۴۰۵',      42.5, 9, 'ثبت خطاهای کلاینت در لاگ متمرکز',                'حجم بالای لاگ‌های تکراری',                  'افزودن جمع‌بندی خطاهای مشابه',          TIMESTAMPTZ '2026-10-10 18:15:00+03:30'),
    ('rep_21', 'usr_121', 'هفته چهارم مهر ۱۴۰۵',    36.5, 6, 'کاهش حجم بسته جاوااسکریپت',                      'وابستگی‌های سنگین قابل حذف نبودند',        'بارگذاری تنبل ماژول‌های کم‌کاربرد',      TIMESTAMPTZ '2026-10-17 16:50:00+03:30'),
    ('rep_22', 'usr_122', 'هفته چهارم مهر ۱۴۰۵',    39.5, 7, 'پیاده‌سازی تنظیمات اعلان کاربر',                  'همگام‌سازی تنظیمات میان دستگاه‌ها',         'افزودن اعلان درون‌برنامه‌ای',            TIMESTAMPTZ '2026-10-17 18:30:00+03:30'),
    ('rep_23', 'usr_123', 'هفته اول آبان ۱۴۰۵',     44.0, 9, 'ساخت کلید شیء و نشانی CDN',                      'تعیین ساختار نام‌گذاری فایل‌ها',            'اتصال به فضای ذخیره‌سازی ابری',         TIMESTAMPTZ '2026-10-24 17:20:00+03:30'),
    ('rep_24', 'usr_124', 'هفته اول آبان ۱۴۰۵',     31.5, 4, 'اعمال سقف حجم آپلود',                            'پیام خطای نامفهوم برای کاربر',             'بازنویسی پیام‌های خطای آپلود',          TIMESTAMPTZ '2026-10-24 15:45:00+03:30'),
    ('rep_25', 'usr_125', 'هفته دوم آبان ۱۴۰۵',     40.0, 7, 'پیکربندی اولیه اپلیکیشن موبایل',                 'تفاوت رفتار ناوبری در اندروید',            'پیاده‌سازی صفحه فهرست تسک‌ها',           TIMESTAMPTZ '2026-10-31 18:00:00+03:30'),
    ('rep_26', 'usr_126', 'هفته دوم آبان ۱۴۰۵',     37.0, 6, 'تدوین راهنمای راه‌اندازی پروژه',                  'اختلاف نسخه ابزارها روی سیستم اعضا',       'افزودن بخش رفع اشکال به راهنما',       TIMESTAMPTZ '2026-10-31 16:25:00+03:30'),
    ('rep_27', 'usr_127', 'هفته سوم آبان ۱۴۰۵',     28.0, 3, 'بررسی اولیه نمودارهای داشبورد',                  'کمبود داده واقعی برای آزمون',              'ساخت داده نمونه برای نمودارها',        TIMESTAMPTZ '2026-11-07 15:10:00+03:30'),
    ('rep_28', 'usr_128', 'هفته سوم آبان ۱۴۰۵',     43.5, 9, 'تبدیل تاریخ میلادی به جلالی',                    'محاسبه درست سال کبیسه شمسی',               'نمایش سررسید تسک‌ها به شمسی',           TIMESTAMPTZ '2026-11-07 17:55:00+03:30'),
    ('rep_29', 'usr_129', 'هفته چهارم آبان ۱۴۰۵',   45.5, 10, 'تفکیک لایه سرویس از لایه REST',                 'وابستگی‌های حلقوی میان کلاس‌ها',            'حذف منطق باقی‌مانده از منابع',          TIMESTAMPTZ '2026-11-14 18:35:00+03:30'),
    ('rep_30', 'usr_130', 'هفته چهارم آبان ۱۴۰۵',   32.5, 5, 'تدوین مسیر یادگیری اعضای جدید',                  'تنوع سطح دانش اعضای تازه‌وارد',            'برگزاری نشست معارفه فنی',              TIMESTAMPTZ '2026-11-14 16:15:00+03:30')
) AS v (id, user_id, week_title, hours_worked, tasks_completed, achievements, challenges, next_week_plan, submitted_at)
JOIN users u ON u.id = v.user_id;

SELECT setval('weekly_reports_id_seq', 30, TRUE);
--rollback DELETE FROM weekly_reports;

--changeset arman:105-seed-chat-messages contextFilter:seed
--comment: 30 messages. sender_name and sender_avatar are read from users for the same
--comment: reason as weekly_reports.user_name. msg_201 reproduces the spec's SSE example.
INSERT INTO chat_messages (id, sender_id, sender_name, sender_avatar, content, file_url, sent_at)
SELECT v.id, v.sender_id, u.name, u.avatar, v.content, v.file_url, v.sent_at
FROM (VALUES
    ('msg_201', 'usr_101', 'سلام همکاران گرامی، گزارش این هفته ثبت شد.',                    NULL,                                          TIMESTAMPTZ '2026-08-08 09:00:00+03:30'),
    ('msg_202', 'usr_102', 'ممنون علی. لطفا جمع ساعات را هم در گزارش بیاورید.',             NULL,                                          TIMESTAMPTZ '2026-08-08 09:04:00+03:30'),
    ('msg_203', 'usr_103', 'صفحه ورود به سرویس احراز هویت وصل شد.',                         NULL,                                          TIMESTAMPTZ '2026-08-08 10:12:00+03:30'),
    ('msg_204', 'usr_104', 'نقش‌های مدیر و دانشجو نهایی شد. مستندش را گذاشتم.',             '/uploads/1754640000_roles.pdf',               TIMESTAMPTZ '2026-08-09 11:30:00+03:30'),
    ('msg_205', 'usr_105', 'زمان بارگذاری صفحه اصلی حدود سی درصد کم شد.',                   NULL,                                          TIMESTAMPTZ '2026-08-10 14:20:00+03:30'),
    ('msg_206', 'usr_106', 'تم تاریک آماده بازبینی است.',                                    '/uploads/1754812800_dark-theme.png',          TIMESTAMPTZ '2026-08-11 16:45:00+03:30'),
    ('msg_207', 'usr_102', 'عالی بود نرگس. فردا در جلسه مرور می‌کنیم.',                     NULL,                                          TIMESTAMPTZ '2026-08-11 16:52:00+03:30'),
    ('msg_208', 'usr_107', 'پیش‌نویس مستندات API را در شاخه docs گذاشتم.',                  NULL,                                          TIMESTAMPTZ '2026-08-12 09:35:00+03:30'),
    ('msg_209', 'usr_108', 'کامپوننت دکمه در سه جا تکراری بود؛ یکی‌شان کردم.',              NULL,                                          TIMESTAMPTZ '2026-08-13 13:10:00+03:30'),
    ('msg_210', 'usr_109', 'صدور توکن دسترسی کار می‌کند. سراغ توکن رفرش می‌روم.',           NULL,                                          TIMESTAMPTZ '2026-08-14 10:05:00+03:30'),
    ('msg_211', 'usr_110', 'در دو کوئری الحاق رشته پیدا کردم. اصلاح شد.',                   NULL,                                          TIMESTAMPTZ '2026-08-17 15:40:00+03:30'),
    ('msg_212', 'usr_111', 'اولین تصویر بومی ساخته شد؛ راه‌اندازی زیر یک ثانیه.',           NULL,                                          TIMESTAMPTZ '2026-08-18 17:25:00+03:30'),
    ('msg_213', 'usr_101', 'تبریک مهدی. زمان ساخت چقدر طول کشید؟',                          NULL,                                          TIMESTAMPTZ '2026-08-18 17:31:00+03:30'),
    ('msg_214', 'usr_111', 'حدود چهار دقیقه روی همین سیستم.',                                NULL,                                          TIMESTAMPTZ '2026-08-18 17:33:00+03:30'),
    ('msg_215', 'usr_112', 'محاسبه ساعات هفتگی با تقویم شمسی اختلاف داشت؛ درست شد.',        NULL,                                          TIMESTAMPTZ '2026-08-19 11:50:00+03:30'),
    ('msg_216', 'usr_113', 'قالب خروجی اکسل آماده است.',                                     '/uploads/1755590400_report-template.xlsx',    TIMESTAMPTZ '2026-08-20 12:15:00+03:30'),
    ('msg_217', 'usr_114', 'روی ستون وضعیت نمایه اضافه کردم، فهرست تسک‌ها سریع‌تر شد.',      NULL,                                          TIMESTAMPTZ '2026-08-21 14:05:00+03:30'),
    ('msg_218', 'usr_115', 'اتصال استریم پشت پراکسی قطع می‌شد؛ با تنظیم بافر حل شد.',       NULL,                                          TIMESTAMPTZ '2026-08-24 10:45:00+03:30'),
    ('msg_219', 'usr_116', 'انتقال داده‌های قدیمی تمام شد. تعداد رکوردها مطابقت دارد.',      NULL,                                          TIMESTAMPTZ '2026-08-25 16:30:00+03:30'),
    ('msg_220', 'usr_117', 'ترتیب حروف فارسی با ICU درست شد: ب پ ت ج چ ک گ.',               NULL,                                          TIMESTAMPTZ '2026-08-26 09:20:00+03:30'),
    ('msg_221', 'usr_104', 'این را حتما در مستندات راه‌اندازی بیاورید.',                     NULL,                                          TIMESTAMPTZ '2026-08-26 09:28:00+03:30'),
    ('msg_222', 'usr_118', 'چند ورودی برچسب نداشتند؛ برای صفحه‌خوان اصلاح شد.',             NULL,                                          TIMESTAMPTZ '2026-08-27 13:55:00+03:30'),
    ('msg_223', 'usr_120', 'لاگ‌های تکراری زیاد بود؛ جمع‌بندی خطاهای مشابه اضافه شد.',      NULL,                                          TIMESTAMPTZ '2026-08-28 15:10:00+03:30'),
    ('msg_224', 'usr_121', 'دو کتابخانه سنگین حذف شدند و بسته کوچک‌تر شد.',                 NULL,                                          TIMESTAMPTZ '2026-08-31 11:40:00+03:30'),
    ('msg_225', 'usr_122', 'تنظیمات اعلان کاربر روی پروفایل ذخیره می‌شود.',                 NULL,                                          TIMESTAMPTZ '2026-09-01 10:25:00+03:30'),
    ('msg_226', 'usr_123', 'ساختار نام‌گذاری فایل‌های آپلودی را اینجا نوشتم.',              '/uploads/1756713600_naming.md',               TIMESTAMPTZ '2026-09-02 14:35:00+03:30'),
    ('msg_227', 'usr_124', 'پیام خطای آپلود بازنویسی شد و فارسی و روشن است.',               NULL,                                          TIMESTAMPTZ '2026-09-03 16:20:00+03:30'),
    ('msg_228', 'usr_125', 'نسخه اول اپلیکیشن موبایل روی اندروید اجرا شد.',                 '/uploads/1756972800_android-build.apk',       TIMESTAMPTZ '2026-09-04 17:50:00+03:30'),
    ('msg_229', 'usr_128', 'تبدیل تاریخ به جلالی با سال کبیسه هم درست کار می‌کند.',         NULL,                                          TIMESTAMPTZ '2026-09-07 09:45:00+03:30'),
    ('msg_230', 'usr_102', 'دست همه درد نکند. جلسه مرور هفتگی پنجشنبه ساعت ده.',            NULL,                                          TIMESTAMPTZ '2026-09-07 18:00:00+03:30')
) AS v (id, sender_id, content, file_url, sent_at)
JOIN users u ON u.id = v.sender_id;

SELECT setval('chat_messages_id_seq', 230, TRUE);
--rollback DELETE FROM chat_messages;
