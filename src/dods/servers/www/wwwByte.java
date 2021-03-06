/////////////////////////////////////////////////////////////////////////////
// Copyright (c) 1999, COAS, Oregon State University  
// ALL RIGHTS RESERVED.   U.S. Government Sponsorship acknowledged. 
//
// Please read the full copyright notice in the file COPYRIGHT
// in this directory.
//
// Author: Nathan Potter (ndp@oce.orst.edu)
//
//                        College of Oceanic and Atmospheric Scieneces
//                        Oregon State University
//                        104 Ocean. Admin. Bldg.
//                        Corvallis, OR 97331-5503
//         
/////////////////////////////////////////////////////////////////////////////

 
package dods.servers.www;
import java.io.*;
import dods.dap.*;

/**
 */
public class wwwByte extends DByte implements BrowserForm {

    private static boolean _Debug = false;

     /** Constructs a new <code>asciiByte</code>. */
    public wwwByte() {
        this(null);
    }

    /**
    * Constructs a new <code>asciiByte</code> with name <code>n</code>.
    * @param n the name of the variable.
    */
    public wwwByte(String n) {
        super(n);
    }
    
    public void printBrowserForm(PrintWriter pw, DAS das){
        wwwOutPut wOut = new wwwOutPut(pw);
        wOut.writeSimpleVar(pw, this);
    }

}
