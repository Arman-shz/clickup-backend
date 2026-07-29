package ir.arman.api.error;

/**
 * The Persian messages the spec puts in its error examples
 * (swagger.yaml, components/responses). Kept in one place so responses stay
 * byte-identical to the documented contract.
 */
public final class ApiMessages {

    /** components/responses/BadRequest */
    public static final String BAD_REQUEST = "اطلاعات ورودی نامعتبر است.";

    /** components/responses/Unauthorized */
    public static final String UNAUTHORIZED = "دسترسی غیرمجاز. لطفا دوباره وارد شوید.";

    /** components/responses/NotFound */
    public static final String NOT_FOUND = "منبع مورد نظر پیدا نشد.";

    /** paths./api/auth/login.post.responses.401 */
    public static final String BAD_CREDENTIALS = "نام کاربری یا رمز عبور اشتباه است";

    /** paths./api/auth/register.post.responses.409 */
    public static final String STUDENT_ID_TAKEN = "این شماره دانشجویی قبلا ثبت شده است.";

    /** paths./api/users/me.put.responses.409 */
    public static final String EMAIL_TAKEN = "این ایمیل قبلا ثبت شده است.";

    /** Any other unique-constraint collision. */
    public static final String CONFLICT = "این اطلاعات قبلا ثبت شده است.";

    /** paths./api/upload.post.responses.400 -- the multipart carried no `file` part. */
    public static final String FILE_REQUIRED = "فایلی ارسال نشده است.";

    /** paths./api/upload.post.responses.400 -- a `file` part with nothing in it. */
    public static final String FILE_EMPTY = "فایل ارسال‌شده خالی است.";

    /** paths./api/upload.post.responses.413 -- over the 50 MiB the spec caps uploads at. */
    public static final String FILE_TOO_LARGE = "حجم فایل بیشتر از حد مجاز است. حداکثر ۵۰ مگابایت.";

    /** components/responses/Forbidden -- a valid token, but not the admin role. */
    public static final String FORBIDDEN = "این عملیات فقط برای مدیر مجاز است.";

    private ApiMessages() {
    }
}
