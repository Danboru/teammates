package teammates.storage.api;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import javax.jdo.FetchPlan;
import javax.jdo.JDOHelper;
import javax.jdo.JDOObjectNotFoundException;
import javax.jdo.PersistenceManager;
import javax.jdo.Query;

import com.google.appengine.api.datastore.DatastoreService;
import com.google.appengine.api.datastore.Key;
import com.google.appengine.api.datastore.KeyFactory;

import teammates.common.datatransfer.AccountAttributes;
import teammates.common.datatransfer.EntityAttributes;
import teammates.common.datatransfer.StudentProfileAttributes;
import teammates.common.exception.EntityAlreadyExistsException;
import teammates.common.exception.EntityDoesNotExistException;
import teammates.common.exception.InvalidParametersException;
import teammates.common.util.Assumption;
import teammates.common.util.Config;
import teammates.common.util.Const;
import teammates.common.util.ThreadHelper;
import teammates.common.util.Utils;
import teammates.storage.entity.Account;
import teammates.storage.entity.StudentProfile;

/**
 * Handles CRUD Operations for accounts.
 * The API uses data transfer classes (i.e. *Attributes) instead of persistable classes.
 * 
 */
public class AccountsDb extends EntitiesDb {

    private static final Logger log = Utils.getLogger();
    
    /**
     * Preconditions: 
     * <br> * {@code accountToAdd} is not null and has valid data.
     */
    public void createAccount(AccountAttributes accountToAdd) 
            throws InvalidParametersException {
        // TODO: use createEntity once there is a proper way to add instructor accounts.
        try {
            createEntity(accountToAdd);
        } catch (EntityAlreadyExistsException e) {
            // We update the account instead if it already exists. This is due to how
            // adding of instructor accounts work.
            try {
                updateAccount(accountToAdd, true);
            } catch (EntityDoesNotExistException edne) {
                // This situation is not tested as replicating such a situation is 
                // difficult during testing
                Assumption.fail("Entity found be already existing and not existing simultaneously");
            }
        }
    }
    
    /**
     * Preconditions: 
     * <br> * All parameters are non-null. 
     * @return Null if not found.
     */
    public AccountAttributes getAccount(String googleId, boolean retrieveStudentProfile) {
        Assumption.assertNotNull(Const.StatusCodes.DBLEVEL_NULL_INPUT, googleId);
        
        Account a = getAccountEntity(googleId, retrieveStudentProfile);
    
        if (a == null) {
            return null;
        }
        closePM();
        
        AccountAttributes accAttr = new AccountAttributes(a);
        return accAttr;
    }
    
    public AccountAttributes getAccount(String googleId) {
        return getAccount(googleId, false);
    }

    /**
     * @return {@link AccountAttribute} objects for all accounts with instructor privileges.
     *   Returns an empty list if no such accounts are found.
     */
    public List<AccountAttributes> getInstructorAccounts() {
        Query q = getPM().newQuery(Account.class);
        q.setFilter("isInstructor == true");
        
        @SuppressWarnings("unchecked")
        List<Account> accountsList = (List<Account>) q.execute();
        
        List<AccountAttributes> instructorsAccountData = new ArrayList<AccountAttributes>();
                
        for (Account a : accountsList) {
            instructorsAccountData.add(new AccountAttributes(a));
        }
        
        closePM();
        
        return instructorsAccountData;
    }

    /**
     * Preconditions: 
     * <br> * {@code accountToAdd} is not null and has valid data.
     */
    public void updateAccount(AccountAttributes a, boolean updateStudentProfile) 
            throws InvalidParametersException, EntityDoesNotExistException {
        Assumption.assertNotNull(Const.StatusCodes.DBLEVEL_NULL_INPUT, a);
        
        if (!a.isValid()) {
            throw new InvalidParametersException(a.getInvalidityInfo());
        }
        
        Account accountToUpdate = getAccountEntity(a.googleId, true);

        if (accountToUpdate == null) {
            throw new EntityDoesNotExistException(ERROR_UPDATE_NON_EXISTENT_ACCOUNT + a.googleId
                + ThreadHelper.getCurrentThreadStack());
        }
        
        a.sanitizeForSaving();
        accountToUpdate.setName(a.name);
        accountToUpdate.setEmail(a.email);
        accountToUpdate.setIsInstructor(a.isInstructor);
        accountToUpdate.setInstitute(a.institute);
        
        // if the student profile has changed then update the store
        // this is to maintain integrity of the modified date.
        if (updateStudentProfile) {
            String existingProfile = new StudentProfileAttributes(accountToUpdate.getStudentProfile()).toString();
            if(!(existingProfile.equals(a.studentProfile.toString()))) {
                accountToUpdate.setStudentProfile((StudentProfile) a.studentProfile.toEntity());
            }
        }
        
        closePM();
    }
    
    public void updateAccount(AccountAttributes a) 
            throws InvalidParametersException, EntityDoesNotExistException {
        updateAccount(a, false);
    }

    /**
     * Note: This is a non-cascade delete. <br>
     *   <br> Fails silently if there is no such account.
     * <br> Preconditions: 
     * <br> * {@code googleId} is not null.
     */
    public void deleteAccount(String googleId) {
        Assumption.assertNotNull(Const.StatusCodes.DBLEVEL_NULL_INPUT, googleId);
        
        Account accountToDelete = getAccountEntity(googleId, true);

        if (accountToDelete == null) {
            return;
        }
        
        accountToDelete.getStudentProfile();
        
        getPM().deletePersistent(accountToDelete);
        getPM().flush();
        closePM();
    
        // Wait for the operation to persist
        int elapsedTime = 0;
        Account accountCheck = getAccountEntity(googleId);
        // the following while loop is not tested as 
        // replicating a persistence delay is difficult during testing
        while ((accountCheck != null)
                && (elapsedTime < Config.PERSISTENCE_CHECK_DURATION)) {
            ThreadHelper.waitBriefly();
            accountCheck = getAccountEntity(googleId);
            elapsedTime += ThreadHelper.WAIT_DURATION;
            closePM();
        }
        if (elapsedTime >= Config.PERSISTENCE_CHECK_DURATION) {
            log.severe("Operation did not persist in time: deleteAccount->"
                    + googleId);
        }
        
        //TODO: Use the delete operation in the parent class instead.
    }

    private Account getAccountEntity(String googleId, boolean retrieveStudentProfile) {
        
        Key key = KeyFactory.createKey(Account.class.getSimpleName(), googleId);
        try {
            Account account = getPM().getObjectById(Account.class, key);
            
            if (JDOHelper.isDeleted(account)) {
                return null;
            } else if (retrieveStudentProfile) {
                account.getStudentProfile();
            }
            
            return account;
        } catch(JDOObjectNotFoundException je) {
            return null;
        }
    }
    
    private Account getAccountEntity(String googleId) {
        
        return getAccountEntity(googleId, false);
    }
    
    public StudentProfileAttributes getStudentProfile(String accountGoogleId) {
        Key childKey = KeyFactory.createKey(Account.class.getSimpleName(), accountGoogleId)
                .getChild(StudentProfile.class.getSimpleName(), accountGoogleId);
        
        try {
            StudentProfile sp = getPM().getObjectById(StudentProfile.class, childKey);
            if (JDOHelper.isDeleted(sp)) {
                return null;
            } else {
                return new StudentProfileAttributes(sp);
            }
        } catch (JDOObjectNotFoundException je) {
            return null;
        }
    }
    
    public StudentProfileAttributes getStudentProfileFromName(String shortName) {
        
        PersistenceManager pm = getPM();
        Query q = pm.newQuery(StudentProfile.class);
        q.declareParameters("String shortNameParam");
        q.setFilter("shortName == shortNameParam");
        
        @SuppressWarnings("unchecked")
        List<StudentProfile> profilesList = (List<StudentProfile>) q.execute(shortName);
        
        if (profilesList.isEmpty() || JDOHelper.isDeleted(profilesList.get(0))) {
            return null;
        } else {
            return new StudentProfileAttributes(profilesList.get(0));
        }
    }
    
    private void closePM() {
        if (!getPM().isClosed()) {
            getPM().close();
        }
    }

    @Override
    protected Object getEntity(EntityAttributes entity) {
        return getAccountEntity(((AccountAttributes)entity).googleId);
    }
    

}

