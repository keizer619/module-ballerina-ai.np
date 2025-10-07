import ballerina/io;

function analyzeEmployeesNPGenerated(Employee[] employees, string department, decimal salary) {
    Employee[] filteredEmployees = employees.filter(employee => employee.department == department && employee.salary > salary);

    foreach Employee employee in filteredEmployees {
        io:println("Employee ID: ", employee.id, ", Name: ", employee.name, ", Salary: ", employee.salary);
    }
}

