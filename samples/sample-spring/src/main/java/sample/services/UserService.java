package sample.services;

import sample.repositories.*;

public class UserService {
    private UserRepository userRepository;
    private int maxUsers;

    public void setUserRepository(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public void onInit() {
    }

    public void onDestroy() {
    }

    public void setMaxUsers(int maxUsers) {
        this.maxUsers = maxUsers;
    }
}