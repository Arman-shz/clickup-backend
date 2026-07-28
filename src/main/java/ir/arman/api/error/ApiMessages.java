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

    private ApiMessages() {
    }
}
