package io.github.frewily.campushub.identity.application;

/** The existing account fields consumed by login, without exposing persistence or CRUD APIs. */
public final class LoginAccount {
    private final Long id;
    private final String nickName;
    private final String icon;
    private final String status;

    public LoginAccount(Long id, String nickName, String icon, String status) {
        this.id = id;
        this.nickName = nickName;
        this.icon = icon;
        this.status = status;
    }

    public Long getId() { return id; }
    public String getNickName() { return nickName; }
    public String getIcon() { return icon; }
    public String getStatus() { return status; }
}
