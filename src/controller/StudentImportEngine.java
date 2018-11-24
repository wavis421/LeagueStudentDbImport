package controller;

import java.util.ArrayList;
import java.util.Collections;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;

import model.AttendanceEventModel;
import model.CoursesModel;
import model.LogDataModel;
import model.MySqlDatabase;
import model.MySqlDbImports;
import model.MySqlDbLogging;
import model.ScheduleModel;
import model.StudentImportModel;
import model.StudentModel;
import model.StudentNameModel;

public class StudentImportEngine {
	static final int SCHEDULE_DAYS_IN_PAST = 14;
	static final int COURSE_DAYS_IN_PAST = 14;
	static final int COURSE_DAYS_IN_FUTURE = 120;

	MySqlDatabase sqlDb;
	MySqlDbImports sqlImportDb;

	public StudentImportEngine(MySqlDatabase sqlDb, MySqlDbImports sqlImportDb) {
		this.sqlDb = sqlDb;
		this.sqlImportDb = sqlImportDb;
	}

	public void importStudentsFromPike13(Pike13Api pike13Api) {
		String today = new DateTime().withZone(DateTimeZone.forID("America/Los_Angeles")).toString("yyyy-MM-dd")
				.substring(0, 10);

		// Get data from Pike13, then update student TA data from Staff DB
		ArrayList<StudentImportModel> studentList = pike13Api.getClients();
		pike13Api.updateStudentTAData(studentList);
		sqlImportDb.updateMissingCurrentClass();
		Collections.sort(studentList);

		// Update changes in database
		if (studentList.size() > 0) {
			sqlImportDb.importStudents(studentList);
			System.out.println(studentList.size() + " students imported from Pike13");
		}
	}

	public void importAttendanceFromPike13(String startDate, Pike13Api pike13Api) {
		// Get attendance data from Pike13 for all students
		ArrayList<AttendanceEventModel> eventList = pike13Api.getAttendance(startDate);

		// Update changes in database
		if (eventList.size() > 0) {
			// Import attendance and then re-sort attendance list
			sqlImportDb.importAttendance(startDate, eventList, true);
			sqlImportDb.createSortedAttendanceList();
			System.out.println(eventList.size() + " attendance records imported from Pike13");
		}

		// Get 'missing' attendance for new and returned students
		ArrayList<StudentModel> newStudents = sqlDb.getStudentsUsingFlag("NewStudent");
		if (newStudents.size() > 0) {
			eventList = pike13Api.getMissingAttendance(startDate, newStudents);
			if (eventList.size() > 0) {
				sqlImportDb.importAttendance(startDate, eventList, false);
				System.out.println(eventList.size() + " new student attendance records imported from Pike13");
			}
		}
	}

	public void importCourseAttendanceFromPike13(String startDate, String endDate, Pike13Api pike13Api) {
		// Get course attendance data from Pike13 for all students
		ArrayList<AttendanceEventModel> eventList = pike13Api.getCourseAttendance(startDate, endDate);

		// Update changes in database
		if (eventList.size() > 0) {
			sqlImportDb.importAttendance(startDate, eventList, false);
			System.out.println(eventList.size() + " course attendance records imported from Pike13, " + startDate
					+ " to " + endDate);
		}
	}

	public void importScheduleFromPike13(Pike13Api pike13Api) {
		String startDate = new DateTime().withZone(DateTimeZone.forID("America/Los_Angeles"))
				.minusDays(SCHEDULE_DAYS_IN_PAST).toString("yyyy-MM-dd");

		// Get data from Pike13
		ArrayList<ScheduleModel> scheduleList = pike13Api.getSchedule(startDate);
		ArrayList<ScheduleModel> filteredList = new ArrayList<ScheduleModel>();

		// Pike13 will have duplicate Schedule ID's, so filter them out
		ScheduleModel lastUpdate = null;
		for (ScheduleModel item : scheduleList) {
			if (lastUpdate != null && item.compareTo(lastUpdate) == 0)
				continue;

			filteredList.add(item);
			lastUpdate = item;
		}

		// Update student age fields and count
		updateScheduleData(filteredList);

		// Update changes in database
		if (filteredList.size() > 0) {
			sqlImportDb.importSchedule(filteredList);
			System.out.println(filteredList.size() + " schedule records imported from Pike13");
		}
	}

	private void updateScheduleData(ArrayList<ScheduleModel> schedule) {
		// Update the age fields and the attendance count for each class in schedule
		ArrayList<StudentModel> students = sqlDb.getActiveStudents();

		for (ScheduleModel sched : schedule) {
			String className = sched.getClassName();
			int attCount = 0, ageCount = 0;
			Double ageMin = 0.0, ageMax = 0.0, ageAvg = 0.0, ageTot = 0.0;
			int[] moduleCnt = new int[10]; // Curr count for modules 0-9

			for (StudentModel stud : students) {
				// Update for each student in this class
				if (className.equals(stud.getCurrentClass())) {
					attCount++;
					if (stud.getAge() > 0) {
						ageCount++;
						ageTot += stud.getAge();
						if (ageMin == 0 || stud.getAge() < ageMin)
							ageMin = stud.getAge();
						if (stud.getAge() > ageMax)
							ageMax = stud.getAge();
						if (stud.getCurrentModule() != null && !stud.getCurrentModule().equals("")
								&& stud.getCurrentModule().charAt(0) >= '0' && stud.getCurrentModule().charAt(0) <= '9')
							moduleCnt[stud.getCurrentModule().charAt(0) - '0']++;
					}
				}
			}
			// Update the fields for this scheduled class
			if (ageCount > 0) {
				String moduleString = "";
				int recordCnt = attCount;
				for (int i = 0; i < moduleCnt.length; i++) {
					if (moduleCnt[i] > 0) {
						recordCnt -= moduleCnt[i];
						if (!moduleString.equals(""))
							moduleString += ", ";
						moduleString += moduleCnt[i] + "@Mod" + i;
					}
				}
				if (recordCnt > 0 && !moduleString.equals(""))
					moduleString += ", " + recordCnt + "@?";
				ageAvg = ageTot / ageCount;
				sched.setMiscSchedFields(attCount, ageMin.toString().substring(0, 4), ageMax.toString().substring(0, 4),
						ageAvg.toString().substring(0, 4), moduleString);
			} else {
				sched.setMiscSchedFields(0, "", "", "", "");
			}
		}
	}

	public void importCoursesFromPike13(Pike13Api pike13Api) {
		DateTime today = new DateTime().withZone(DateTimeZone.forID("America/Los_Angeles"));
		String startDate = today.minusDays(COURSE_DAYS_IN_PAST).toString("yyyy-MM-dd");
		String endDate = today.plusDays(COURSE_DAYS_IN_FUTURE).toString("yyyy-MM-dd");

		// Get data from Pike13
		ArrayList<CoursesModel> coursesList = pike13Api.getCourses(startDate, endDate);

		// Update changes in database
		if (coursesList.size() > 0) {
			sqlImportDb.importCourses(coursesList);
			System.out.println(coursesList.size() + " course records imported from Pike13");
		}
	}

	public void importGithubComments(String startDate, GithubApi githubApi) {
		boolean result;

		// Update github comments for users with new user name
		githubApi.updateMissingGithubComments();

		// Get list of events with missing comments
		ArrayList<AttendanceEventModel> eventList = sqlDb.getEventsWithNoComments(startDate, 0, false);
		if (eventList.size() > 0) {
			// Import Github comments
			result = githubApi.importGithubComments(startDate, eventList);

			if (result) {
				// Import github comments for level 0 - 5, plus Intro to Java (-1)
				githubApi.importGithubCommentsByLevel(-1, startDate, null, eventList);
				githubApi.importGithubCommentsByLevel(0, startDate, null, eventList);
				githubApi.importGithubCommentsByLevel(1, startDate, null, eventList);
				githubApi.importGithubCommentsByLevel(2, startDate, null, eventList);
				githubApi.importGithubCommentsByLevel(3, startDate, null, eventList);
				githubApi.importGithubCommentsByLevel(4, startDate, null, eventList);
				githubApi.importGithubCommentsByLevel(5, startDate, null, eventList);

				// Update any remaining null comments to show event was processed
				githubApi.updateEmptyGithubComments(eventList);

				System.out.println(eventList.size() + " github records imported");

			} else {
				MySqlDbLogging.insertLogData(LogDataModel.GITHUB_IMPORT_ABORTED, new StudentNameModel("", "", false), 0,
						": Github API rate limit exceeded ***");
				return;
			}
		}
	}
}
